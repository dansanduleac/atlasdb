/*
 * Copyright 2015 Palantir Technologies, Inc. All rights reserved.
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.palantir.atlasdb.server;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.palantir.atlasdb.config.ImmutableAtlasDbRuntimeConfig;
import com.palantir.atlasdb.config.ImmutableSweepConfig;
import com.palantir.atlasdb.factory.TransactionManagers;
import com.palantir.atlasdb.server.generated.TodoSchemaTableFactory;
import com.palantir.atlasdb.server.generated.TodoTable;
import com.palantir.atlasdb.table.description.Schema;
import com.palantir.atlasdb.transaction.impl.SerializableTransactionManager;
import com.palantir.atlasdb.util.AtlasDbMetrics;
import com.palantir.remoting3.servers.jersey.HttpRemotingJerseyFeature;
import com.palantir.tritium.metrics.MetricRegistries;

import io.dropwizard.Application;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;

public class AtlasDbServiceServer extends Application<AtlasDbServiceServerConfiguration> {
    private static final Logger log = LoggerFactory.getLogger(AtlasDbServiceServer.class);
    private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy.MM.dd.HH.mm.ss");

    private static SerializableTransactionManager tm;

    public static void main(String[] args) throws Exception {
        new AtlasDbServiceServer().run(args);
        runTxns();
    }

    @Override
    public void initialize(Bootstrap<AtlasDbServiceServerConfiguration> bootstrap) {
        super.initialize(bootstrap);
        bootstrap.getObjectMapper().registerModule(new Jdk8Module());
        bootstrap.setMetricRegistry(MetricRegistries.createWithHdrHistogramReservoirs());
    }

    @Override
    public void run(AtlasDbServiceServerConfiguration config, final Environment environment) throws Exception {
        AtlasDbMetrics.setMetricRegistry(environment.metrics());
        environment.jersey().register(HttpRemotingJerseyFeature.INSTANCE);
        Schema schema = TodoSchema.getSchema();
        schema.renderTables(new File("src/main/java/schema"));

        tm = TransactionManagers.builder()
                .config(config.getConfig())
                .runtimeConfigSupplier(() -> Optional.of(ImmutableAtlasDbRuntimeConfig.builder()
                        .sweep(ImmutableSweepConfig.builder().enabled(false).build())
                        .build()))
                .registrar(environment.jersey()::register)
                .schemas(ImmutableSet.of(TodoSchema.getSchema()))
                .userAgent("AtlasDbServiceServer")
                .buildSerializable();
    }

    private static void runTxns() throws FileNotFoundException {
        File file = new File("output.txt");
        PrintStream printStream = new PrintStream(new BufferedOutputStream(new FileOutputStream(file)));
        System.setOut(printStream);
        while (true) {
            try {
                tm.runTaskWithRetry(txn -> {
                    TodoSchemaTableFactory tableFactory = TodoSchemaTableFactory.of();
                    TodoTable todoTable = tableFactory.getTodoTable(txn);
                    todoTable.putText(TodoTable.TodoRow.of(7), "yeah");
                    todoTable.putText(TodoTable.TodoRow.of(1), "nopes");
                    return null;
                });
            } catch (Exception e) {
                System.out.println("OHH NO! looks like the tm throws something." + e.getMessage());
                e.printStackTrace(System.out);
                System.out.println("=============================================================================");
            } finally {
                System.out.flush();
            }

            try {
                List<TodoTable.TodoRowResult> todoRowResults = tm.runTaskWithRetry(txn -> {
                    TodoSchemaTableFactory tableFactory = TodoSchemaTableFactory.of();
                    TodoTable todoTable = tableFactory.getTodoTable(txn);
                    return todoTable.getRows(ImmutableList.of(TodoTable.TodoRow.of(7), TodoTable.TodoRow.of(1)));
                });
                log.info("{}", todoRowResults);
            } catch (Exception e) {
                System.out.println("OHH NO! looks like the tm throws something." + e.getMessage());
                e.printStackTrace(System.out);
                System.out.println("=============================================================================");
            } finally {
                System.out.flush();
            }

            try {
                tm.runTaskWithRetry(txn -> {
                    TodoSchemaTableFactory tableFactory = TodoSchemaTableFactory.of();
                    TodoTable todoTable = tableFactory.getTodoTable(txn);
                    todoTable.delete(ImmutableList.of(TodoTable.TodoRow.of(7)));
                    return null;
                });
            } catch (Exception e) {
                System.out.println("OHH NO! looks like the tm throws something." + e.getMessage());
                e.printStackTrace(System.out);
                System.out.println("=============================================================================");
            } finally {
                System.out.flush();
            }
        }
    }
}
