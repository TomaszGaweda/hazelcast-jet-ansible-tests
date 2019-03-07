/*
 * Copyright (c) 2008-2018, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.jet.tests.earlyresults;

import com.hazelcast.jet.Job;
import com.hazelcast.jet.aggregate.AggregateOperations;
import com.hazelcast.jet.config.JobConfig;
import com.hazelcast.jet.datamodel.KeyedWindowResult;
import com.hazelcast.jet.pipeline.Pipeline;
import com.hazelcast.jet.pipeline.Sink;
import com.hazelcast.jet.pipeline.SinkBuilder;
import com.hazelcast.jet.pipeline.StreamStage;
import com.hazelcast.jet.pipeline.WindowDefinition;
import com.hazelcast.jet.tests.common.AbstractSoakTest;

import java.util.HashMap;
import java.util.Map;

import static com.hazelcast.jet.core.JobStatus.FAILED;
import static com.hazelcast.jet.tests.common.Util.getJobStatus;
import static com.hazelcast.jet.tests.common.Util.sleepMinutes;

public class EarlyResultsTest extends AbstractSoakTest {

    private static final int ONE_THOUSAND = 1000;
    private static final int DEFAULT_WINDOW_SIZE = 100;
    private static final int DEFAULT_TRADE_PER_SECOND = 20;

    private int windowSize;
    private int tradePerSecond;
    private long earlyResultsPeriod;

    public static void main(String[] args) throws Exception {
        new EarlyResultsTest().run(args);
    }

    @Override
    protected void init() {
        windowSize = propertyInt("windowSize", DEFAULT_WINDOW_SIZE);
        tradePerSecond = propertyInt("tradePerSecond", DEFAULT_TRADE_PER_SECOND);
        earlyResultsPeriod = windowSize * ONE_THOUSAND / tradePerSecond / 3;
    }

    @Override
    protected void test() {
        JobConfig jobConfig = new JobConfig();
        jobConfig.setName(EarlyResultsTest.class.getSimpleName());
        Job job = jet.newJob(pipeline(), jobConfig);

        long begin = System.currentTimeMillis();
        while (System.currentTimeMillis() - begin < durationInMillis) {
            assertNotEquals(getJobStatus(job), FAILED);
            sleepMinutes(1);
        }

        job.cancel();
    }

    private Pipeline pipeline() {
        Pipeline p = Pipeline.create();

        Sink<KeyedWindowResult<String, Long>> verificationSink = SinkBuilder
                .sinkBuilder("verification", c -> new VerificationContext(windowSize))
                .receiveFn(VerificationContext::verify)
                .build();

        StreamStage<Map.Entry<String, Long>> sourceStage = p.drawFrom(TradeGenerator.tradeSource(tradePerSecond))
                                          .withNativeTimestamps(0)
                                          .setName("Stream from EarlyResult-TradeGenerator");

        sourceStage.groupingKey(Map.Entry::getKey)
                   .window(WindowDefinition.tumbling(windowSize).setEarlyResultsPeriod(earlyResultsPeriod))
                   .aggregate(AggregateOperations.counting())
                   .drainTo(verificationSink);

        return p;
    }

    @Override
    protected void teardown() {
    }

    static class VerificationContext {

        private final int windowSize;
        private Map<String, TickerWindow> tickerMap = new HashMap<>();

        VerificationContext(int windowSize) {
            this.windowSize = windowSize;
        }

        void verify(KeyedWindowResult<String, Long> result) {
            TickerWindow tickerWindow = tickerMap.computeIfAbsent(result.getKey(), TickerWindow::new);
            assertEquals(tickerWindow.start, result.start());
            if (result.isEarly()) {
                assertTrue(windowSize >= result.getValue());
                tickerWindow.hasEarly = true;
            } else {
                assertTrue(tickerWindow.hasEarly);
                assertEquals(windowSize, (long) result.getValue());
                tickerWindow.advance();
            }
        }

        class TickerWindow {

            private final String key;
            private long start;
            private boolean hasEarly;

            TickerWindow(String key) {
                this.key = key;
            }

            void advance() {
                start += windowSize;
                hasEarly = false;
            }
        }
    }
}