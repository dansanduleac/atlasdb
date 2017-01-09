/**
 * Copyright 2017 Palantir Technologies
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
package com.palantir.atlasdb.timelock.config;

import java.io.File;

import org.immutables.value.Value;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.palantir.atlasdb.timelock.ServerImplementation;
import com.palantir.atlasdb.timelock.paxos.PaxosServerImplementation;
import com.palantir.remoting.ssl.SslConfiguration;

import io.dropwizard.setup.Environment;

@JsonSerialize(as = ImmutablePaxosConfiguration.class)
@JsonDeserialize(as = ImmutablePaxosConfiguration.class)
@Value.Immutable
public abstract class PaxosConfiguration implements AlgorithmConfiguration {
    @Value.Default
    public File paxosDataDir() {
        return new File("var/data/paxos");
    }

    public abstract Optional<SslConfiguration> sslConfiguration();

    @Value.Default
    public long pingRateMs() {
        return 5000L;
    }

    @Value.Default
    public long randomWaitBeforeProposingLeadershipMs() {
        return 1000L;
    }

    @Value.Default
    public long leaderPingResponseWaitMs() {
        return 5000L;
    }

    @Value.Check
    protected final void check() {
        Preconditions.checkArgument(paxosDataDir().exists() || paxosDataDir().mkdirs(),
                "Paxos data directory '%s' does not exist and cannot be created.", paxosDataDir());
    }

    @Override
    public ServerImplementation createServerImpl(Environment environment) {
        return new PaxosServerImplementation(environment);
    }
}
