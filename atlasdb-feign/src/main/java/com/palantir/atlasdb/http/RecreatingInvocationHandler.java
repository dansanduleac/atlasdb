/*
 * Copyright 2017 Palantir Technologies, Inc. All rights reserved.
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

package com.palantir.atlasdb.http;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.reflect.AbstractInvocationHandler;
import com.google.common.reflect.Reflection;
import com.palantir.logsafe.UnsafeArg;

/**
 * This class servers as a wrapper for a dynamic proxy of type D; typically T is a configuration type
 * used for D. This works by detecting changes in values supplied from a {@link Supplier} of type T, and
 * re-creating the object of type D if a change in the value supplied is detected.
 *
 * This class should NOT be used when D itself accepts live-reloadable configuration, as it incurs overhead
 * in creating a new instance of type D.
 *
 * This class uses a pull-based model, where the underlying Supplier is called every time a request to this
 * dynamic proxy is made. If making such requests is expensive, it is suggested that users provide
 * suppliers that memoize these values appropriately.
 *
 * @param <T> type of the input to create a delegate
 * @param <D> type of the delegate
 */

public final class RecreatingInvocationHandler<T, D> extends AbstractInvocationHandler {
    private static final Logger log = LoggerFactory.getLogger(RecreatingInvocationHandler.class);

    private final Supplier<Optional<T>> deltaSupplier;
    private final Function<T, D> delegateCreator;

    private volatile D activeDelegate;

    private RecreatingInvocationHandler(Supplier<Optional<T>> supplier, Function<T, D> delegateCreator) {
        this.deltaSupplier = supplier;
        this.delegateCreator = delegateCreator;

        T initialValue = supplier.get().orElseThrow(() ->
                new IllegalStateException("First return value of supplier should be nonempty"));
        activeDelegate = delegateCreator.apply(initialValue);
    }

    public static <T, D> D create(
            Supplier<T> supplier,
            Function<T, D> delegateCreator,
            Class<D> delegateType) {
        return createWithRawDeltaSupplier(wrapInDeltaSupplier(supplier), delegateCreator, delegateType);
    }

    @VisibleForTesting
    static <T> Supplier<Optional<T>> wrapInDeltaSupplier(Supplier<T> supplier) {
        return new DeltaSupplier<>(supplier);
    }

    @VisibleForTesting
    static <T, D> D createWithRawDeltaSupplier(
            Supplier<Optional<T>> supplier,
            Function<T, D> delegateCreator,
            Class<D> delegateType) {
        return Reflection.newProxy(delegateType, new RecreatingInvocationHandler<>(supplier, delegateCreator));
    }

    @Override
    protected Object handleInvocation(Object proxy, Method method, Object[] args) throws Throwable {
        updateDelegateIfNeeded();
        try {
            return method.invoke(activeDelegate, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private void updateDelegateIfNeeded() {
        deltaSupplier.get().ifPresent(input -> {
            log.info("Updating delegate because we observed the input change to {}.",
                    UnsafeArg.of("input", input));
            activeDelegate = delegateCreator.apply(input);
        });
    }

    /**
     * A DeltaSupplier wraps a {@link Supplier} and stores its last known value.
     * {@link this::get()} returns Optional.empty() if and only if the value returned from the base Supplier
     * is the same as the value obtained when it was last called.
     *
     * In the event of multiple concurrent calls.
     * However, if a supplier
     *
     * @param <T> the type of the underlying supplier
     */
    private static final class DeltaSupplier<T> implements Supplier<Optional<T>> {
        private static final Logger log = LoggerFactory.getLogger(DeltaSupplier.class);

        private final Supplier<T> baseSupplier;
        private final AtomicReference<T> lastSeenValue = new AtomicReference<>();

        private DeltaSupplier(Supplier<T> baseSupplier) {
            this.baseSupplier = baseSupplier;
        }

        @Override
        public Optional<T> get() {
            T actualValue = baseSupplier.get();
            T currentLastSeenValue = lastSeenValue.get();
            while (!actualValue.equals(currentLastSeenValue)) {
                log.info("Attempting to update the value from {} to {}",
                        UnsafeArg.of("currentValue", currentLastSeenValue),
                        UnsafeArg.of("targetValue", actualValue));
                if (lastSeenValue.compareAndSet(currentLastSeenValue, actualValue)) {
                    log.info("Updated the value to {}",
                            UnsafeArg.of("updatedValue", actualValue));
                    return Optional.of(actualValue);
                }
                currentLastSeenValue = lastSeenValue.get();
            }
            // We didn't change the value.
            log.debug("Did not update the value from {} because that's also what we got from the delegate.",
                    UnsafeArg.of("currentValue", currentLastSeenValue));
            return Optional.empty();
        }
    }
}