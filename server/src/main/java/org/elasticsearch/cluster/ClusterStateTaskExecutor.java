/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.cluster;

import org.apache.cassandra.db.Mutation;
import org.apache.cassandra.transport.Event;
import org.elasticsearch.common.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public interface ClusterStateTaskExecutor<T> {
    /**
     * Update the cluster state based on the current state and the given tasks. Return the *same instance* if no state
     * should be changed.
     */
    ClusterTasksResult<T> execute(ClusterState currentState, List<T> tasks) throws Exception;

    /**
     * indicates whether this executor should only run if the current node is master
     */
    default boolean runOnlyOnMaster() {
        return true;
    }

    /**
     * Callback invoked after new cluster state is published. Note that
     * this method is not invoked if the cluster state was not updated.
     * @param clusterChangedEvent the change event for this cluster state change, containing
     *                            both old and new states
     */
    default void clusterStatePublished(ClusterChangedEvent clusterChangedEvent) {
    }

    /**
     * Builds a concise description of a list of tasks (to be used in logging etc.).
     *
     * Note that the tasks given are not necessarily the same as those that will be passed to {@link #execute(ClusterState, List)}.
     * but are guaranteed to be a subset of them. This method can be called multiple times with different lists before execution.
     * This allows groupd task description but the submitting source.
     */
    default String describeTasks(List<T> tasks) {
        return tasks.stream().map(T::toString).reduce((s1,s2) -> {
            if (s1.isEmpty()) {
                return s2;
            } else if (s2.isEmpty()) {
                return s1;
            } else {
                return s1 + ", " + s2;
            }
        }).orElse("");
    }

    /**
     * Represents the result of a batched execution of cluster state update tasks
     * @param <T> the type of the cluster state update task
     */
    class ClusterTasksResult<T> {
        @Nullable
        public final ClusterState resultingState;
        public final Map<T, TaskResult> executionResults;
        public final boolean doPresistMetaData;
        public final Collection<Mutation> mutations;
        public final Collection<Event.SchemaChange> events;

        /**
         * Construct an execution result instance with a correspondence between the tasks and their execution result
         * @param resultingState the resulting cluster state
         * @param executionResults the correspondence between tasks and their outcome
         */
        ClusterTasksResult(ClusterState resultingState, Map<T, TaskResult> executionResults) {
            this(resultingState, executionResults, false);
        }

        ClusterTasksResult(ClusterState resultingState, Map<T, TaskResult> executionResults, boolean doPresistMetaData) {
            this(resultingState, executionResults, doPresistMetaData, Collections.EMPTY_LIST, Collections.EMPTY_LIST);
        }

        ClusterTasksResult(ClusterState resultingState, Map<T, TaskResult> executionResults, boolean doPresistMetaData, Collection<Mutation> cqlMutations, Collection<Event.SchemaChange> events) {
            this.resultingState = resultingState;
            this.executionResults = executionResults;
            this.doPresistMetaData = doPresistMetaData;
            this.mutations = cqlMutations;
            this.events = events;
        }

        public static <T> Builder<T> builder() {
            return new Builder<>();
        }

        public static class Builder<T> {
            private final Map<T, TaskResult> executionResults = new IdentityHashMap<>();

            public Builder<T> success(T task) {
                return result(task, TaskResult.success());
            }

            public Builder<T> successes(Iterable<T> tasks) {
                for (T task : tasks) {
                    success(task);
                }
                return this;
            }

            public Builder<T> failure(T task, Exception e) {
                return result(task, TaskResult.failure(e));
            }

            public Builder<T> failures(Iterable<T> tasks, Exception e) {
                for (T task : tasks) {
                    failure(task, e);
                }
                return this;
            }

            private Builder<T> result(T task, TaskResult executionResult) {
                TaskResult existing = executionResults.put(task, executionResult);
                assert existing == null : task + " already has result " + existing;
                return this;
            }

            public ClusterTasksResult<T> build(ClusterState resultingState) {
                return new ClusterTasksResult<>(resultingState, executionResults);
            }

            public ClusterTasksResult<T> build(ClusterState resultingState, boolean doPresistMetaData) {
                return new ClusterTasksResult<>(resultingState, executionResults, doPresistMetaData, Collections.EMPTY_LIST, Collections.EMPTY_LIST);
            }

            public ClusterTasksResult<T> build(ClusterState resultingState, boolean doPresistMetaData, Collection<Mutation> cqlMutations, Collection<Event.SchemaChange> events) {
                return new ClusterTasksResult<>(resultingState, executionResults, doPresistMetaData, cqlMutations, events);
            }

            ClusterTasksResult<T> build(ClusterTasksResult<T> result, ClusterState previousState) {
                return new ClusterTasksResult<>(result.resultingState == null ? previousState : result.resultingState,
                    executionResults, false);
            }

            ClusterTasksResult<T> build(ClusterTasksResult<T> result, ClusterState previousState, boolean doPresistMetaData, Collection<Mutation> cqlMutations, Collection<Event.SchemaChange> events) {
                return new ClusterTasksResult<>(result.resultingState == null ? previousState : result.resultingState,
                    executionResults, doPresistMetaData, cqlMutations, events);
            }
        }
    }

    final class TaskResult {
        private final Exception failure;

        private static final TaskResult SUCCESS = new TaskResult(null);

        public static TaskResult success() {
            return SUCCESS;
        }

        public static TaskResult failure(Exception failure) {
            return new TaskResult(failure);
        }

        private TaskResult(Exception failure) {
            this.failure = failure;
        }

        public boolean isSuccess() {
            return this == SUCCESS;
        }

        public Exception getFailure() {
            assert !isSuccess();
            return failure;
        }
    }
}
