// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "tensor_function.h"
#include "value.h"
#include "operation.h"
#include "tensor.h"
#include "tensor_engine.h"
#include "simple_tensor_engine.h"
#include "visit_stuff.h"
#include "string_stuff.h"
#include <vespa/eval/instruction/generic_concat.h>
#include <vespa/eval/instruction/generic_create.h>
#include <vespa/eval/instruction/generic_join.h>
#include <vespa/eval/instruction/generic_map.h>
#include <vespa/eval/instruction/generic_merge.h>
#include <vespa/eval/instruction/generic_peek.h>
#include <vespa/eval/instruction/generic_reduce.h>
#include <vespa/eval/instruction/generic_rename.h>
#include <vespa/vespalib/objects/objectdumper.h>
#include <vespa/vespalib/objects/visit.hpp>

#include <vespa/log/log.h>
LOG_SETUP(".eval.eval.tensor_function");

namespace vespalib {
namespace eval {

vespalib::string
TensorFunction::as_string() const
{
    ObjectDumper dumper;
    ::visit(dumper, "", *this);
    return dumper.toString();
}

void
TensorFunction::visit_self(vespalib::ObjectVisitor &visitor) const
{
    visitor.visitString("result_type", result_type().to_spec());
    visitor.visitBool("result_is_mutable", result_is_mutable());
}

void
TensorFunction::visit_children(vespalib::ObjectVisitor &visitor) const
{
    std::vector<vespalib::eval::TensorFunction::Child::CREF> children;
    push_children(children);
    for (size_t i = 0; i < children.size(); ++i) {
        vespalib::string name = vespalib::make_string("children[%zu]", i);
        ::visit(visitor, name, children[i].get().get());
    }
}

namespace tensor_function {

namespace {

using State = InterpretedFunction::State;
using Instruction = InterpretedFunction::Instruction;

//-----------------------------------------------------------------------------

uint64_t to_param(map_fun_t value) { return (uint64_t)value; }
uint64_t to_param(join_fun_t value) { return (uint64_t)value; }
map_fun_t to_map_fun(uint64_t param) { return (map_fun_t)param; }
join_fun_t to_join_fun(uint64_t param) { return (join_fun_t)param; }

//-----------------------------------------------------------------------------

void op_load_const(State &state, uint64_t param) {
    state.stack.push_back(unwrap_param<Value>(param));
}

//-----------------------------------------------------------------------------

void op_double_map(State &state, uint64_t param) {
    state.pop_push(state.stash.create<DoubleValue>(to_map_fun(param)(state.peek(0).as_double())));
}

void op_double_mul(State &state, uint64_t) {
    state.pop_pop_push(state.stash.create<DoubleValue>(state.peek(1).as_double() * state.peek(0).as_double()));
}

void op_double_add(State &state, uint64_t) {
    state.pop_pop_push(state.stash.create<DoubleValue>(state.peek(1).as_double() + state.peek(0).as_double()));
}

void op_double_join(State &state, uint64_t param) {
    state.pop_pop_push(state.stash.create<DoubleValue>(to_join_fun(param)(state.peek(1).as_double(), state.peek(0).as_double())));
}

//-----------------------------------------------------------------------------

void op_tensor_map(State &state, uint64_t param) {
    state.pop_push(state.engine.map(state.peek(0), to_map_fun(param), state.stash));
}

void op_tensor_join(State &state, uint64_t param) {
    state.pop_pop_push(state.engine.join(state.peek(1), state.peek(0), to_join_fun(param), state.stash));
}

void op_tensor_merge(State &state, uint64_t param) {
    state.pop_pop_push(state.engine.merge(state.peek(1), state.peek(0), to_join_fun(param), state.stash));
}

using ReduceParams = std::pair<Aggr,std::vector<vespalib::string>>;
void op_tensor_reduce(State &state, uint64_t param) {
    const ReduceParams &params = unwrap_param<ReduceParams>(param);
    state.pop_push(state.engine.reduce(state.peek(0), params.first, params.second, state.stash));
}

using RenameParams = std::pair<std::vector<vespalib::string>,std::vector<vespalib::string>>;
void op_tensor_rename(State &state, uint64_t param) {
    const RenameParams &params = unwrap_param<RenameParams>(param);
    state.pop_push(state.engine.rename(state.peek(0), params.first, params.second, state.stash));
}

void op_tensor_concat(State &state, uint64_t param) {
    const vespalib::string &dimension = unwrap_param<vespalib::string>(param);
    state.pop_pop_push(state.engine.concat(state.peek(1), state.peek(0), dimension, state.stash));
}

void op_tensor_create(State &state, uint64_t param) {
    const Create &self = unwrap_param<Create>(param);
    TensorSpec spec(self.result_type().to_spec());
    size_t i = 0;
    for (auto pos = self.spec().rbegin(); pos != self.spec().rend(); ++pos) {
        spec.add(pos->first, state.peek(i++).as_double());
    }
    const Value &result = *state.stash.create<Value::UP>(state.engine.from_spec(spec));
    state.pop_n_push(i, result);
}

struct LambdaParams {
    const Lambda &parent;
    InterpretedFunction fun;
    LambdaParams(const Lambda &parent_in, InterpretedFunction fun_in)
        : parent(parent_in), fun(std::move(fun_in)) {}
};

void op_tensor_lambda(State &state, uint64_t param) {
    const LambdaParams &params = unwrap_param<LambdaParams>(param);
    TensorSpec spec = params.parent.create_spec(*state.params, params.fun);
    const Value &result = *state.stash.create<Value::UP>(state.engine.from_spec(spec));
    state.stack.emplace_back(result);
}

const Value &extract_single_value(const TensorSpec &spec, const TensorSpec::Address &addr, State &state) {
    auto pos = spec.cells().find(addr);
    if (pos == spec.cells().end()) {
        return state.stash.create<DoubleValue>(0.0);
    }
    return state.stash.create<DoubleValue>(pos->second);
}

const Value &extract_tensor_subspace(const ValueType &my_type, const TensorSpec &spec, const TensorSpec::Address &addr, State &state) {
    TensorSpec my_spec(my_type.to_spec());
    for (const auto &cell: spec.cells()) {
        bool keep = true;
        TensorSpec::Address my_addr;
        for (const auto &binding: cell.first) {
            auto pos = addr.find(binding.first);
            if (pos == addr.end()) {
                my_addr.emplace(binding.first, binding.second);
            } else {
                if (!(pos->second == binding.second)) {
                    keep = false;
                }
            }
        }
        if (keep) {
            my_spec.add(my_addr, cell.second);
        }
    }
    return *state.stash.create<Value::UP>(state.engine.from_spec(my_spec));
}

void op_tensor_peek(State &state, uint64_t param) {
    const Peek &self = unwrap_param<Peek>(param);
    TensorSpec::Address addr;
    size_t child_cnt = 0;
    for (auto pos = self.spec().rbegin(); pos != self.spec().rend(); ++pos) {
        std::visit(vespalib::overload
                   {
                       [&](const TensorSpec::Label &label) {
                           addr.emplace(pos->first, label);
                       },
                       [&](const TensorFunction::Child &) {
                           double index = state.peek(child_cnt++).as_double();
                           size_t dim_idx = self.param_type().dimension_index(pos->first);
                           assert(dim_idx != ValueType::Dimension::npos);
                           const auto &param_dim = self.param_type().dimensions()[dim_idx];
                           if (param_dim.is_mapped()) {
                               addr.emplace(pos->first, vespalib::make_string("%" PRId64, int64_t(index)));
                           } else {
                               addr.emplace(pos->first, size_t(index));
                           }
                       }
                   }, pos->second);
    }
    TensorSpec spec = state.engine.to_spec(state.peek(child_cnt++));
    const Value &result = self.result_type().is_double()
                          ? extract_single_value(spec, addr, state)
                          : extract_tensor_subspace(self.result_type(), spec, addr, state);
    state.pop_n_push(child_cnt, result);
}

} // namespace vespalib::eval::tensor_function::<unnamed>

//-----------------------------------------------------------------------------

void
Leaf::push_children(std::vector<Child::CREF> &) const
{
}

//-----------------------------------------------------------------------------

void
Op1::push_children(std::vector<Child::CREF> &children) const
{
    children.emplace_back(_child);
}

void
Op1::visit_children(vespalib::ObjectVisitor &visitor) const
{
    ::visit(visitor, "child", _child.get());
}

//-----------------------------------------------------------------------------

void
Op2::push_children(std::vector<Child::CREF> &children) const
{
    children.emplace_back(_lhs);
    children.emplace_back(_rhs);
}

void
Op2::visit_children(vespalib::ObjectVisitor &visitor) const
{
    ::visit(visitor, "lhs", _lhs.get());
    ::visit(visitor, "rhs", _rhs.get());
}

//-----------------------------------------------------------------------------

Instruction
ConstValue::compile_self(EngineOrFactory, Stash &) const
{
    return Instruction(op_load_const, wrap_param<Value>(_value));
}

void
ConstValue::visit_self(vespalib::ObjectVisitor &visitor) const
{
    Super::visit_self(visitor);
    if (result_type().is_double()) {
        visitor.visitFloat("value", _value.as_double());
    } else {
        visitor.visitString("value", "...");
    }
}

//-----------------------------------------------------------------------------

Instruction
Inject::compile_self(EngineOrFactory, Stash &) const
{
    return Instruction::fetch_param(_param_idx);
}

void
Inject::visit_self(vespalib::ObjectVisitor &visitor) const
{
    Super::visit_self(visitor);
    visitor.visitInt("param_idx", _param_idx);
}

//-----------------------------------------------------------------------------

Instruction
Reduce::compile_self(EngineOrFactory engine, Stash &stash) const
{
    if (engine.is_factory()) {
        return instruction::GenericReduce::make_instruction(child().result_type(), aggr(), dimensions(), engine.factory(), stash);
    }
    ReduceParams &params = stash.create<ReduceParams>(_aggr, _dimensions);
    return Instruction(op_tensor_reduce, wrap_param<ReduceParams>(params));
}

void
Reduce::visit_self(vespalib::ObjectVisitor &visitor) const
{
    Super::visit_self(visitor);
    ::visit(visitor, "aggr", _aggr);
    ::visit(visitor, "dimensions", visit::DimList(_dimensions));
}

//-----------------------------------------------------------------------------

Instruction
Map::compile_self(EngineOrFactory engine, Stash &) const
{
    if (engine.is_factory()) {
        return instruction::GenericMap::make_instruction(result_type(), _function);
    }
    if (result_type().is_double()) {
        return Instruction(op_double_map, to_param(_function));
    }
    return Instruction(op_tensor_map, to_param(_function));
}

void
Map::visit_self(vespalib::ObjectVisitor &visitor) const
{
    Super::visit_self(visitor);
    ::visit(visitor, "function", _function);
}

//-----------------------------------------------------------------------------

Instruction
Join::compile_self(EngineOrFactory engine, Stash &stash) const
{
    if (engine.is_factory()) {
        return instruction::GenericJoin::make_instruction(lhs().result_type(), rhs().result_type(), function(), engine.factory(), stash);
    }
    if (result_type().is_double()) {
        if (_function == operation::Mul::f) {
            return Instruction(op_double_mul);
        }
        if (_function == operation::Add::f) {
            return Instruction(op_double_add);
        }
        return Instruction(op_double_join, to_param(_function));
    }
    return Instruction(op_tensor_join, to_param(_function));
}

void
Join::visit_self(vespalib::ObjectVisitor &visitor) const
{
    Super::visit_self(visitor);
    ::visit(visitor, "function", _function);
}

//-----------------------------------------------------------------------------

Instruction
Merge::compile_self(EngineOrFactory engine, Stash &stash) const
{
    if (engine.is_factory()) {
        return instruction::GenericMerge::make_instruction(lhs().result_type(), rhs().result_type(), function(), engine.factory(), stash);
    }
    return Instruction(op_tensor_merge, to_param(_function));
}

void
Merge::visit_self(vespalib::ObjectVisitor &visitor) const
{
    Super::visit_self(visitor);
    ::visit(visitor, "function", _function);
}

//-----------------------------------------------------------------------------

Instruction
Concat::compile_self(EngineOrFactory engine, Stash &stash) const
{
    if (engine.is_factory()) {
        return instruction::GenericConcat::make_instruction(lhs().result_type(), rhs().result_type(), dimension(), engine.factory(), stash);
    }
    return Instruction(op_tensor_concat, wrap_param<vespalib::string>(_dimension));
}

void
Concat::visit_self(vespalib::ObjectVisitor &visitor) const
{
    Super::visit_self(visitor);
    visitor.visitString("dimension", _dimension);
}

//-----------------------------------------------------------------------------

void
Create::push_children(std::vector<Child::CREF> &children) const
{
    for (const auto &cell: _spec) {
        children.emplace_back(cell.second);
    }
}

Instruction
Create::compile_self(EngineOrFactory engine, Stash &stash) const
{
    if (engine.is_factory()) {
        std::map<TensorSpec::Address, size_t> generic_spec;
        size_t child_idx = 0;
        for (const auto & kv : spec()) {
            generic_spec[kv.first] = child_idx++;
        }
        return instruction::GenericCreate::make_instruction(result_type(), generic_spec, engine.factory(), stash);
    }
    return Instruction(op_tensor_create, wrap_param<Create>(*this));
}

void
Create::visit_children(vespalib::ObjectVisitor &visitor) const
{
    for (const auto &cell: _spec) {
        ::visit(visitor, ::vespalib::eval::as_string(cell.first), cell.second.get());
    }
}

//-----------------------------------------------------------------------------

namespace {

bool step_labels(std::vector<size_t> &labels, const ValueType &type) {
    for (size_t idx = labels.size(); idx-- > 0; ) {
        if (++labels[idx] < type.dimensions()[idx].size) {
            return true;
        } else {
            labels[idx] = 0;
        }
    }
    return false;
}

struct ParamProxy : public LazyParams {
    const std::vector<size_t> &labels;
    const LazyParams          &params;
    const std::vector<size_t> &bindings;
    ParamProxy(const std::vector<size_t> &labels_in, const LazyParams &params_in, const std::vector<size_t> &bindings_in)
        : labels(labels_in), params(params_in), bindings(bindings_in) {}
    const Value &resolve(size_t idx, Stash &stash) const override {
        if (idx < labels.size()) {
            return stash.create<DoubleValue>(labels[idx]);
        }
        return params.resolve(bindings[idx - labels.size()], stash);
    }
};

}

TensorSpec
Lambda::create_spec_impl(const ValueType &type, const LazyParams &params, const std::vector<size_t> &bind, const InterpretedFunction &fun)
{
    std::vector<size_t> labels(type.dimensions().size(), 0);
    ParamProxy param_proxy(labels, params, bind);
    InterpretedFunction::Context ctx(fun);
    TensorSpec spec(type.to_spec());
    do {
        TensorSpec::Address address;
        for (size_t i = 0; i < labels.size(); ++i) {
            address.emplace(type.dimensions()[i].name, labels[i]);
        }
        spec.add(std::move(address), fun.eval(ctx, param_proxy).as_double());
    } while (step_labels(labels, type));
    return spec;
}

InterpretedFunction::Instruction
Lambda::compile_self(EngineOrFactory engine, Stash &stash) const
{
    InterpretedFunction fun(engine, _lambda->root(), _lambda_types);
    LambdaParams &params = stash.create<LambdaParams>(*this, std::move(fun));
    return Instruction(op_tensor_lambda, wrap_param<LambdaParams>(params));
}

void
Lambda::visit_self(vespalib::ObjectVisitor &visitor) const
{
    Super::visit_self(visitor);
    ::visit(visitor, "bindings", _bindings);
}

//-----------------------------------------------------------------------------

void
Peek::push_children(std::vector<Child::CREF> &children) const
{
    children.emplace_back(_param);
    for (const auto &dim: _spec) {
        std::visit(vespalib::overload
                   {
                       [&](const Child &child) {
                           children.emplace_back(child);
                       },
                       [](const TensorSpec::Label &) noexcept {}
                   }, dim.second);
    }
}

Instruction
Peek::compile_self(EngineOrFactory engine, Stash &stash) const
{
    if (engine.is_factory()) {
        instruction::GenericPeek::SpecMap generic_spec;
        size_t child_idx = 0;
        for (const auto & kv : spec()) {
            std::visit(vespalib::overload {
                    [&](const TensorSpec::Label &label) {
                        generic_spec.emplace(kv.first, label);
                    },
                    [&](const TensorFunction::Child &) {
                        generic_spec.emplace(kv.first, child_idx++);
                    }
                }, kv.second);
        }
        return instruction::GenericPeek::make_instruction(param_type(), result_type(), generic_spec, engine.factory(), stash);
    }
    return Instruction(op_tensor_peek, wrap_param<Peek>(*this));
}

void
Peek::visit_children(vespalib::ObjectVisitor &visitor) const
{
    ::visit(visitor, "param", _param.get());
    for (const auto &dim: _spec) {
        std::visit(vespalib::overload
                   {
                       [&](const TensorSpec::Label &label) {
                           if (label.is_mapped()) {
                               ::visit(visitor, dim.first, label.name);
                           } else {
                               ::visit(visitor, dim.first, static_cast<int64_t>(label.index));
                           }
                       },
                       [&](const Child &child) {
                           ::visit(visitor, dim.first, child.get());
                       }
                   }, dim.second);
    }
}

//-----------------------------------------------------------------------------

Instruction
Rename::compile_self(EngineOrFactory engine, Stash &stash) const
{
    if (engine.is_factory()) {
        return instruction::GenericRename::make_instruction(child().result_type(), from(), to(), engine.factory(), stash);
    }
    RenameParams &params = stash.create<RenameParams>(_from, _to);
    return Instruction(op_tensor_rename, wrap_param<RenameParams>(params));
}

void
Rename::visit_self(vespalib::ObjectVisitor &visitor) const
{
    Super::visit_self(visitor);
    ::visit(visitor, "from_to", visit::FromTo(_from, _to));
}

//-----------------------------------------------------------------------------

void
If::push_children(std::vector<Child::CREF> &children) const
{
    children.emplace_back(_cond);
    children.emplace_back(_true_child);
    children.emplace_back(_false_child);
}

Instruction
If::compile_self(EngineOrFactory, Stash &) const
{
    // 'if' is handled directly by compile_tensor_function to enable
    // lazy-evaluation of true/false sub-expressions.
    LOG_ABORT("should not be reached");
}

void
If::visit_children(vespalib::ObjectVisitor &visitor) const
{
    ::visit(visitor, "cond", _cond.get());
    ::visit(visitor, "true_child", _true_child.get());
    ::visit(visitor, "false_child", _false_child.get());
}

//-----------------------------------------------------------------------------

const TensorFunction &const_value(const Value &value, Stash &stash) {
    return stash.create<ConstValue>(value);
}

const TensorFunction &inject(const ValueType &type, size_t param_idx, Stash &stash) {
    return stash.create<Inject>(type, param_idx);
}

const TensorFunction &reduce(const TensorFunction &child, Aggr aggr, const std::vector<vespalib::string> &dimensions, Stash &stash) {
    ValueType result_type = child.result_type().reduce(dimensions);
    return stash.create<Reduce>(result_type, child, aggr, dimensions);
}

const TensorFunction &map(const TensorFunction &child, map_fun_t function, Stash &stash) {
    ValueType result_type = child.result_type();
    return stash.create<Map>(result_type, child, function);
}

const TensorFunction &join(const TensorFunction &lhs, const TensorFunction &rhs, join_fun_t function, Stash &stash) {
    ValueType result_type = ValueType::join(lhs.result_type(), rhs.result_type());
    return stash.create<Join>(result_type, lhs, rhs, function);
}

const TensorFunction &merge(const TensorFunction &lhs, const TensorFunction &rhs, join_fun_t function, Stash &stash) {
    ValueType result_type = ValueType::merge(lhs.result_type(), rhs.result_type());
    return stash.create<Merge>(result_type, lhs, rhs, function);
}

const TensorFunction &concat(const TensorFunction &lhs, const TensorFunction &rhs, const vespalib::string &dimension, Stash &stash) {
    ValueType result_type = ValueType::concat(lhs.result_type(), rhs.result_type(), dimension);
    return stash.create<Concat>(result_type, lhs, rhs, dimension);
}

const TensorFunction &create(const ValueType &type, const std::map<TensorSpec::Address,TensorFunction::CREF> &spec, Stash &stash) {
    return stash.create<Create>(type, spec);
}

const TensorFunction &lambda(const ValueType &type, const std::vector<size_t> &bindings, const Function &function, NodeTypes node_types, Stash &stash) {
    return stash.create<Lambda>(type, bindings, function, std::move(node_types));
}

const TensorFunction &peek(const TensorFunction &param, const std::map<vespalib::string, std::variant<TensorSpec::Label, TensorFunction::CREF>> &spec, Stash &stash) {
    std::vector<vespalib::string> dimensions;
    for (const auto &dim_spec: spec) {
        dimensions.push_back(dim_spec.first);
    }
    ValueType result_type = param.result_type().reduce(dimensions);
    return stash.create<Peek>(result_type, param, spec);
}

const TensorFunction &rename(const TensorFunction &child, const std::vector<vespalib::string> &from, const std::vector<vespalib::string> &to, Stash &stash) {
    ValueType result_type = child.result_type().rename(from, to);
    return stash.create<Rename>(result_type, child, from, to);
}

const TensorFunction &if_node(const TensorFunction &cond, const TensorFunction &true_child, const TensorFunction &false_child, Stash &stash) {
    ValueType result_type = ValueType::either(true_child.result_type(), false_child.result_type());
    return stash.create<If>(result_type, cond, true_child, false_child);
}

} // namespace vespalib::eval::tensor_function
} // namespace vespalib::eval
} // namespace vespalib
