// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.document.restapi.resource;

import com.yahoo.application.container.DocumentAccesses;
import com.yahoo.cloud.config.ClusterListConfig;
import com.yahoo.docproc.jdisc.metric.NullMetric;
import com.yahoo.document.FixedBucketSpaces;
import com.yahoo.document.config.DocumentmanagerConfig;
import com.yahoo.document.restapi.DocumentOperationExecutorConfig;
import com.yahoo.document.restapi.DocumentOperationExecutorImpl;
import com.yahoo.jdisc.Metric;
import com.yahoo.metrics.simple.MetricReceiver;
import com.yahoo.searchdefinition.derived.Deriver;
import com.yahoo.vespa.config.content.AllClustersBucketSpacesConfig;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

public class DocumentV1ApiTestHandler extends DocumentV1ApiHandler {

    static final AllClustersBucketSpacesConfig bucketConfig = new AllClustersBucketSpacesConfig.Builder()
            .cluster("content",
                     new AllClustersBucketSpacesConfig.Cluster.Builder()
                             .documentType("music",
                                           new AllClustersBucketSpacesConfig.Cluster.DocumentType.Builder()
                                                   .bucketSpace(FixedBucketSpaces.defaultSpace())))
            .build();

    static final ClusterListConfig clusterConfig = new ClusterListConfig.Builder()
            .storage(new ClusterListConfig.Storage.Builder().configid("config-id")
                                                            .name("content"))
            .build();

    static final DocumentOperationExecutorConfig executorConfig = new DocumentOperationExecutorConfig.Builder()
            .resendDelayMillis(10)
            .defaultTimeoutSeconds(1)
            .maxThrottled(2)
            .build();

    static final DocumentmanagerConfig docConfig = Deriver.getDocumentManagerConfig("src/test/cfg/music.sd").build();

    static final Clock clock = Clock.systemUTC();

    public DocumentV1ApiTestHandler() {
        super(clock,
              new DocumentOperationExecutorImpl(clusterConfig, bucketConfig, executorConfig,
                                                DocumentAccesses.createFromSchemas("src/test/cfg/"), clock),
              new DocumentOperationParser(docConfig),
              new NullMetric(),
              new MetricReceiver.MockReceiver());
    }

    private static class DummyMetric implements Metric {

        private final Map<Context, Map<String, Number>> values = new ConcurrentHashMap<>();

        @Override
        public void set(String key, Number val, Context ctx) {
            values.computeIfAbsent(ctx, __ -> new ConcurrentSkipListMap<>()).put(key, val);
        }

        @Override
        public void add(String key, Number val, Context ctx) {
            values.computeIfAbsent(ctx, __ -> new ConcurrentSkipListMap<>()).merge(key, val, (o, n) -> o.doubleValue() + n.doubleValue());
        }

        @Override
        public Context createContext(Map<String, ?> properties) {
            return new MapContext(properties);
        }

        private static class MapContext implements Context {

            private final Map<String, ?> properties;

            private MapContext(Map<String, ?> properties) { this.properties = properties; }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (o == null || getClass() != o.getClass()) return false;
                MapContext that = (MapContext) o;
                return properties.equals(that.properties);
            }

            @Override
            public int hashCode() {
                return Objects.hash(properties);
            }

        }

    }

}
