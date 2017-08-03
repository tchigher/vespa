// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
#include "search_session.h"
#include "match_tools.h"
#include "match_context.h"

namespace proton::matching {

SearchSession::SearchSession(const SessionId &id, fastos::TimeStamp time_of_doom,
                             std::unique_ptr<MatchToolsFactory> match_tools_factory,
                             OwnershipBundle &&owned_objects)
    : _session_id(id),
      _create_time(fastos::ClockSystem::now()),
      _time_of_doom(time_of_doom),
      _owned_objects(std::move(owned_objects)),
      _match_tools_factory(std::move(match_tools_factory))
{
}

void
SearchSession::releaseEnumGuards() {
    _owned_objects.context->releaseEnumGuards();
}

SearchSession::~SearchSession() { }

SearchSession::OwnershipBundle::OwnershipBundle() { }
SearchSession::OwnershipBundle::~OwnershipBundle() { }

}
