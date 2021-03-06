/*
 * Copyright 2011 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.util.internal;

import io.netty.logging.InternalLogger;
import io.netty.logging.InternalLoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Warn when user creates too many instances to avoid {@link OutOfMemoryError}.
 */
public class SharedResourceMisuseDetector {

    private static final int MAX_ACTIVE_INSTANCES = 256;
    private static final InternalLogger logger =
        InternalLoggerFactory.getInstance(SharedResourceMisuseDetector.class);

    private final Class<?> type;
    private final AtomicLong activeInstances = new AtomicLong();
    private final AtomicBoolean logged = new AtomicBoolean();

    public SharedResourceMisuseDetector(Class<?> type) {
        if (type == null) {
            throw new NullPointerException("type");
        }
        this.type = type;
    }

    public void increase() {
        if (activeInstances.incrementAndGet() > MAX_ACTIVE_INSTANCES) {
            if (logger.isWarnEnabled()) {
                if (logged.compareAndSet(false, true)) {
                    logger.warn(
                            "You are creating too many " + type.getSimpleName() +
                            " instances.  " + type.getSimpleName() +
                            " is a shared resource that must be reused across the" +
                            " application, so that only a few instances are created.");
                }
            }
        }
    }

    public void decrease() {
        activeInstances.decrementAndGet();
    }
}
