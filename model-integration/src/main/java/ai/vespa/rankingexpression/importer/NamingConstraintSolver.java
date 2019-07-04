// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package ai.vespa.rankingexpression.importer;

import com.yahoo.collections.ListMap;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Solves a dimension naming constraint problem.
 *
 * @author lesters
 * @author bratseth
 */
class NamingConstraintSolver {

    private final ListMap<String, Integer> possibleAssignments;
    private final ListMap<DimensionRenamer.Arc, DimensionRenamer.Constraint> constraints;

    private int iterations = 0;
    private final int maxIterations;

    private NamingConstraintSolver(Set<String> dimensions,
                                   ListMap<DimensionRenamer.Arc, DimensionRenamer.Constraint> constraints,
                                   int maxIterations) {
        this.possibleAssignments = allPossibilities(dimensions);
        this.constraints = constraints;
        this.maxIterations = maxIterations;
    }

    /** Returns a list containing a list of all assignment possibilities for each of the given dimensions */
    private static ListMap<String, Integer> allPossibilities(Set<String> dimensions) {
        ListMap<String, Integer> all = new ListMap<>();
        for (String dimension : dimensions) {
            for (int i = 0; i < dimensions.size(); ++i)
                all.put(dimension, i);
        }
        return all;
    }

    /** Try the solve the constraint problem given in the arguments, and put the result in renames */
    private Map<String, Integer> trySolve() {
        // TODO: Evaluate possible improved efficiency by using a heuristic such as min-conflicts

        Map<String, Integer> solution = new HashMap<>();
        for (String dimension : possibleAssignments.keySet()) {
            List<Integer> values = possibleAssignments.get(dimension);
            if (values.size() > 1) {
                if ( ! ac3()) return null;
                values.sort(Integer::compare);
                possibleAssignments.replace(dimension, values.get(0));
            }
            solution.put(dimension, possibleAssignments.get(dimension).get(0));
            if (iterations > maxIterations) return null;
        }
        return solution;
    }

    private boolean ac3() {
        Deque<DimensionRenamer.Arc> workList = new ArrayDeque<>(constraints.keySet());
        while ( ! workList.isEmpty()) {
            DimensionRenamer.Arc arc = workList.pop();
            iterations++;
            if (revise(arc)) {
                if (possibleAssignments.get(arc.from).isEmpty()) return false;

                for (DimensionRenamer.Arc constraint : constraints.keySet()) {
                    if (arc.from.equals(constraint.to) && !arc.to.equals(constraint.from))
                        workList.add(constraint);
                }
            }
        }
        return true;
    }

    private boolean revise(DimensionRenamer.Arc arc) {
        boolean revised = false;
        for (Iterator<Integer> fromIterator = possibleAssignments.get(arc.from).iterator(); fromIterator.hasNext(); ) {
            Integer from = fromIterator.next();
            boolean satisfied = false;
            for (Iterator<Integer> toIterator = possibleAssignments.get(arc.to).iterator(); toIterator.hasNext(); ) {
                Integer to = toIterator.next();
                if (constraints.get(arc).stream().allMatch(constraint -> constraint.test(from, to)))
                    satisfied = true;
            }
            if ( ! satisfied) {
                fromIterator.remove();
                revised = true;
            }
        }
        return revised;
    }

    /**
     * Attempts to solve the given naming problem. The input maps are never modified.
     *
     * @return the solution as a map from existing names to name ids represented as integers, or NULL
     *         if no solution could be found
     */
    public static Map<String, Integer> solve(Set<String> dimensions,
                                             ListMap<DimensionRenamer.Arc, DimensionRenamer.Constraint> constraints,
                                             int maxIterations) {
        return new NamingConstraintSolver(dimensions, constraints, maxIterations).trySolve();
    }

}
