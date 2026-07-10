/*
 * Copyright @ 2018 - present 8x8, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jitsi.nlj.util;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public class ExecutorUtils
{
    /**
     * Shutdown {@code executorService} normally via {@link ExecutorService#shutdown()}. If, after
     * {@code timeout} / 2, the service has still not shutdown, try to stop it more forcefully via
     * {@link ExecutorService#shutdownNow()}. If, after {@code timeout} / 2, the service has still not shutdown
     * then throw {@link ExecutorShutdownTimeoutException}.
     */
    public static void safeShutdown(ExecutorService executorService, Duration timeout)
        throws ExecutorShutdownTimeoutException
    {
        executorService.shutdown();
        try
        {
            if (!executorService.awaitTermination(timeout.toMillis() / 2, TimeUnit.MILLISECONDS))
            {
                executorService.shutdownNow();
                if (!executorService.awaitTermination(timeout.toMillis() / 2, TimeUnit.MILLISECONDS))
                {
                    throw new ExecutorShutdownTimeoutException();
                }
            }
        }
        catch (InterruptedException e)
        {
            // Kotlin's ExecutorService.awaitTermination() extension does not declare a checked exception; wrap it.
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
