// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include <string>
#include <vector>
#include <vespa/searchlib/fef/blueprint.h>
#include <vespa/searchlib/fef/featureexecutor.h>

namespace search {
namespace fef {
namespace test {

class ChainExecutor : public FeatureExecutor
{
public:
    ChainExecutor();
    virtual void execute(MatchData & data);
};


class ChainBlueprint : public Blueprint
{
public:
    ChainBlueprint();
    virtual void visitDumpFeatures(const IIndexEnvironment &, IDumpFeatureVisitor &) const {}
    virtual Blueprint::UP createInstance() const { return Blueprint::UP(new ChainBlueprint()); }
    virtual bool setup(const IIndexEnvironment & indexEnv, const StringVector & params);
    virtual FeatureExecutor::LP createExecutor(const IQueryEnvironment & queryEnv) const override {
        (void) queryEnv;
        return FeatureExecutor::LP(new ChainExecutor());
    }
};

} // namespace test
} // namespace fef
} // namespace search

