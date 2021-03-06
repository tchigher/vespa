// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.model.application.validation.change.search;

import com.yahoo.config.provision.ClusterSpec;
import com.yahoo.documentmodel.NewDocumentType;
import com.yahoo.config.application.api.ValidationOverrides;
import com.yahoo.vespa.model.application.validation.change.VespaConfigChangeAction;
import com.yahoo.vespa.model.search.DocumentDatabase;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates the changes between a current and next document database that is part of an indexed search cluster.
 *
 * @author geirst
 */
public class DocumentDatabaseChangeValidator {

    private final ClusterSpec.Id id;
    private final DocumentDatabase currentDatabase;
    private final NewDocumentType currentDocType;
    private final DocumentDatabase nextDatabase;
    private final NewDocumentType nextDocType;

    public DocumentDatabaseChangeValidator(ClusterSpec.Id id,
                                           DocumentDatabase currentDatabase,
                                           NewDocumentType currentDocType,
                                           DocumentDatabase nextDatabase,
                                           NewDocumentType nextDocType) {
        this.id = id;
        this.currentDatabase = currentDatabase;
        this.currentDocType = currentDocType;
        this.nextDatabase = nextDatabase;
        this.nextDocType = nextDocType;
    }

    public List<VespaConfigChangeAction> validate(ValidationOverrides overrides, Instant now) {
        List<VespaConfigChangeAction> result = new ArrayList<>();
        result.addAll(validateAttributeChanges(overrides, now));
        result.addAll(validateStructFieldAttributeChanges(overrides, now));
        result.addAll(validateIndexingScriptChanges(overrides, now));
        result.addAll(validateDocumentTypeChanges(overrides, now));
        return result;
    }

    private List<VespaConfigChangeAction> validateAttributeChanges(ValidationOverrides overrides, Instant now) {
        return new AttributeChangeValidator(id,
                                            currentDatabase.getDerivedConfiguration().getAttributeFields(),
                                            currentDatabase.getDerivedConfiguration().getIndexSchema(), currentDocType,
                                            nextDatabase.getDerivedConfiguration().getAttributeFields(),
                                            nextDatabase.getDerivedConfiguration().getIndexSchema(), nextDocType)
                       .validate(overrides, now);
    }

    private List<VespaConfigChangeAction> validateStructFieldAttributeChanges(ValidationOverrides overrides, Instant now) {
        return new StructFieldAttributeChangeValidator(id,
                                                       currentDocType,
                                                       currentDatabase.getDerivedConfiguration().getAttributeFields(),
                                                       nextDocType,
                                                       nextDatabase.getDerivedConfiguration().getAttributeFields())
                       .validate(overrides, now);
    }

    private List<VespaConfigChangeAction> validateIndexingScriptChanges(ValidationOverrides overrides, Instant now) {
        return new IndexingScriptChangeValidator(id,
                                                 currentDatabase.getDerivedConfiguration().getSearch(),
                                                 nextDatabase.getDerivedConfiguration().getSearch())
                       .validate(overrides, now);
    }

    private List<VespaConfigChangeAction> validateDocumentTypeChanges(ValidationOverrides overrides, Instant now) {
        return new DocumentTypeChangeValidator(id, currentDocType, nextDocType)
                       .validate(overrides, now);
    }

}
