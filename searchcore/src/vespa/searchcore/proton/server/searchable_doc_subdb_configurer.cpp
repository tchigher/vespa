// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "reconfig_params.h"
#include "searchable_doc_subdb_configurer.h"
#include <vespa/searchcore/proton/matching/matcher.h>
#include <vespa/searchcore/proton/attribute/attribute_writer.h>
#include <vespa/searchcore/proton/attribute/imported_attributes_repo.h>
#include <vespa/searchcore/proton/common/document_type_inspector.h>
#include <vespa/searchcore/proton/common/indexschema_inspector.h>
#include <vespa/searchcore/proton/reference/i_document_db_reference_resolver.h>
#include <vespa/searchcore/proton/reprocessing/attribute_reprocessing_initializer.h>
#include <vespa/eval/eval/llvm/compile_cache.h>

using namespace vespa::config::search;
using namespace config;
using search::index::Schema;
using searchcorespi::IndexSearchable;
using document::DocumentTypeRepo;
using vespa::config::search::RankProfilesConfig;

namespace proton {

using matching::Matcher;
using matching::OnnxModels;

typedef AttributeReprocessingInitializer::Config ARIConfig;

void
SearchableDocSubDBConfigurer::reconfigureFeedView(const SearchView::SP &searchView)
{
    SearchableFeedView::SP curr = _feedView.get();
    reconfigureFeedView(curr->getIndexWriter(),
                        curr->getSummaryAdapter(),
                        curr->getAttributeWriter(),
                        curr->getSchema(),
                        curr->getDocumentTypeRepo(),
                        searchView,
                        curr->getLidReuseDelayerConfig());
}

void
SearchableDocSubDBConfigurer::reconfigureFeedView(const IIndexWriter::SP &indexWriter,
                                                  const ISummaryAdapter::SP &summaryAdapter,
                                                  IAttributeWriter::SP attrWriter,
                                                  const Schema::SP &schema,
                                                  const std::shared_ptr<const DocumentTypeRepo> &repo,
                                                  const SearchView::SP &searchView,
                                                  const LidReuseDelayerConfig & lidReuseDelayerConfig)
{
    SearchableFeedView::SP curr = _feedView.get();
    _feedView.set(std::make_shared<SearchableFeedView>(
            StoreOnlyFeedView::Context(summaryAdapter,
                    schema,
                    searchView->getDocumentMetaStore(),
                    curr->getGidToLidChangeHandler(),
                    repo,
                    curr->getWriteService(),
                    lidReuseDelayerConfig),
            curr->getPersistentParams(),
            FastAccessFeedView::Context(std::move(attrWriter), curr->getDocIdLimit()),
            SearchableFeedView::Context(indexWriter)));
}

void
SearchableDocSubDBConfigurer::
reconfigureMatchView(const IndexSearchable::SP &indexSearchable)
{
    SearchView::SP curr = _searchView.get();
    reconfigureMatchView(curr->getMatchers(),
                         indexSearchable,
                         curr->getAttributeManager());
}

void
SearchableDocSubDBConfigurer::
reconfigureMatchView(const Matchers::SP &matchers,
                     const IndexSearchable::SP &indexSearchable,
                     const IAttributeManager::SP &attrMgr)
{
    SearchView::SP curr = _searchView.get();
    auto matchView = std::make_shared<MatchView>(matchers, indexSearchable, attrMgr, curr->getSessionManager(),
                                                 curr->getDocumentMetaStore(), curr->getDocIdLimit());
    reconfigureSearchView(matchView);
}

void
SearchableDocSubDBConfigurer::reconfigureSearchView(MatchView::SP matchView)
{
    SearchView::SP curr = _searchView.get();
    // make sure the initial search does not spend time waiting for
    // expression compilation completion during rank program setup.
    vespalib::eval::CompileCache::wait_pending();
    _searchView.set(SearchView::create(curr->getSummarySetup(), std::move(matchView)));
}

void
SearchableDocSubDBConfigurer::reconfigureSearchView(ISummaryManager::ISummarySetup::SP summarySetup,
                                                    MatchView::SP matchView)
{
    _searchView.set(SearchView::create(std::move(summarySetup), std::move(matchView)));
}

SearchableDocSubDBConfigurer::
SearchableDocSubDBConfigurer(const ISummaryManager::SP &summaryMgr,
                             SearchViewHolder &searchView,
                             FeedViewHolder &feedView,
                             matching::QueryLimiter &queryLimiter,
                             matching::ConstantValueRepo &constantValueRepo,
                             const vespalib::Clock &clock,
                             const vespalib::string &subDbName,
                             uint32_t distributionKey) :
    _summaryMgr(summaryMgr),
    _searchView(searchView),
    _feedView(feedView),
    _queryLimiter(queryLimiter),
    _constantValueRepo(constantValueRepo),
    _clock(clock),
    _subDbName(subDbName),
    _distributionKey(distributionKey)
{ }

SearchableDocSubDBConfigurer::~SearchableDocSubDBConfigurer() = default;

Matchers::UP
SearchableDocSubDBConfigurer::createMatchers(const Schema::SP &schema,
                                             const RankProfilesConfig &cfg,
                                             const OnnxModels &onnxModels)
{
    auto newMatchers = std::make_unique<Matchers>(_clock, _queryLimiter, _constantValueRepo);
    for (const auto &profile : cfg.rankprofile) {
        vespalib::string name = profile.name;
        search::fef::Properties properties;
        for (const auto &property : profile.fef.property) {
            properties.add(property.name, property.value);
        }
        // schema instance only used during call.
        auto profptr = std::make_shared<Matcher>(*schema, properties, _clock, _queryLimiter, _constantValueRepo, onnxModels, _distributionKey);
        newMatchers->add(name, profptr);
    }
    return newMatchers;
}

void
SearchableDocSubDBConfigurer::reconfigureIndexSearchable()
{
    SearchableFeedView::SP feedView(_feedView.get());
    const IIndexWriter::SP &indexWriter = feedView->getIndexWriter();
    const searchcorespi::IIndexManager::SP &indexManager = indexWriter->getIndexManager();
    reconfigureMatchView(indexManager->getSearchable());
    const SearchView::SP searchView(_searchView.get());
    reconfigureFeedView(searchView);
}

void
SearchableDocSubDBConfigurer::
reconfigure(const DocumentDBConfig &newConfig,
            const DocumentDBConfig &oldConfig,
            const ReconfigParams &params,
            IDocumentDBReferenceResolver &resolver)
{
    assert(!params.shouldAttributeManagerChange());
    AttributeCollectionSpec attrSpec(AttributeCollectionSpec::AttributeList(), 0, 0);
    reconfigure(newConfig, oldConfig, attrSpec, params, resolver);
}

namespace {

IReprocessingInitializer::UP
createAttributeReprocessingInitializer(const DocumentDBConfig &newConfig,
                                       const IAttributeManager::SP &newAttrMgr,
                                       const DocumentDBConfig &oldConfig,
                                       const IAttributeManager::SP &oldAttrMgr,
                                       const vespalib::string &subDbName,
                                       search::SerialNum serialNum)
{
    const document::DocumentType *newDocType = newConfig.getDocumentType();
    const document::DocumentType *oldDocType = oldConfig.getDocumentType();
    assert(newDocType != nullptr);
    assert(oldDocType != nullptr);
    DocumentTypeInspector inspector(*oldDocType, *newDocType);
    IndexschemaInspector oldIndexschemaInspector(oldConfig.getIndexschemaConfig());
    return std::make_unique<AttributeReprocessingInitializer>
        (ARIConfig(newAttrMgr, *newConfig.getSchemaSP()),
         ARIConfig(oldAttrMgr, *oldConfig.getSchemaSP()),
         inspector, oldIndexschemaInspector, subDbName, serialNum);
}

}

IReprocessingInitializer::UP
SearchableDocSubDBConfigurer::reconfigure(const DocumentDBConfig &newConfig,
                                          const DocumentDBConfig &oldConfig,
                                          const AttributeCollectionSpec &attrSpec,
                                          const ReconfigParams &params,
                                          IDocumentDBReferenceResolver &resolver)
{
    bool shouldMatchViewChange = false;
    bool shouldSearchViewChange = false;
    bool shouldFeedViewChange = params.shouldSchemaChange();

    SearchView::SP searchView = _searchView.get();
    Matchers::SP matchers = searchView->getMatchers();
    if (params.shouldMatchersChange()) {
        _constantValueRepo.reconfigure(newConfig.getRankingConstants());
        Matchers::SP newMatchers = createMatchers(newConfig.getSchemaSP(),
                                                  newConfig.getRankProfilesConfig(),
                                                  newConfig.getOnnxModels());
        matchers = newMatchers;
        shouldMatchViewChange = true;
    }
    IReprocessingInitializer::UP initializer;
    IAttributeManager::SP attrMgr = searchView->getAttributeManager();
    IAttributeWriter::SP attrWriter = _feedView.get()->getAttributeWriter();
    if (params.shouldAttributeManagerChange()) {
        IAttributeManager::SP newAttrMgr = attrMgr->create(attrSpec);
        newAttrMgr->setImportedAttributes(resolver.resolve(*newAttrMgr, *attrMgr,
                                                           searchView->getDocumentMetaStore(),
                                                           newConfig.getMaintenanceConfigSP()->getVisibilityDelay()));
        IAttributeManager::SP oldAttrMgr = attrMgr;
        attrMgr = newAttrMgr;
        shouldMatchViewChange = true;

        auto newAttrWriter = std::make_shared<AttributeWriter>(newAttrMgr);
        attrWriter = newAttrWriter;
        shouldFeedViewChange = true;
        initializer = createAttributeReprocessingInitializer(newConfig, newAttrMgr, oldConfig, oldAttrMgr,
                                                             _subDbName, attrSpec.getCurrentSerialNum());
    } else if (params.shouldAttributeWriterChange()) {
        attrWriter = std::make_shared<AttributeWriter>(attrMgr);
        shouldFeedViewChange = true;
    }

    ISummaryManager::ISummarySetup::SP sumSetup = _searchView.get()->getSummarySetup();
    if (params.shouldSummaryManagerChange() ||
        params.shouldAttributeManagerChange())
    {
        ISummaryManager::SP sumMgr(_summaryMgr);
        ISummaryManager::ISummarySetup::SP newSumSetup =
            sumMgr->createSummarySetup(newConfig.getSummaryConfig(),
                                       newConfig.getSummarymapConfig(),
                                       newConfig.getJuniperrcConfig(),
                                       newConfig.getDocumentTypeRepoSP(),
                                       attrMgr);
        sumSetup = newSumSetup;
        shouldSearchViewChange = true;
    }

    if (shouldMatchViewChange) {
        IndexSearchable::SP indexSearchable = searchView->getIndexSearchable();
        reconfigureMatchView(matchers, indexSearchable, attrMgr);
        searchView = _searchView.get();
        shouldFeedViewChange = true;
    }

    if (shouldSearchViewChange) {
        reconfigureSearchView(sumSetup, searchView->getMatchView());
    }

    if (shouldFeedViewChange) {
        SearchableFeedView::SP curr = _feedView.get();
        reconfigureFeedView(curr->getIndexWriter(),
                            curr->getSummaryAdapter(),
                            std::move(attrWriter),
                            newConfig.getSchemaSP(),
                            newConfig.getDocumentTypeRepoSP(),
                            searchView,
                            LidReuseDelayerConfig(newConfig));
    }
    return initializer;
}


} // namespace proton
