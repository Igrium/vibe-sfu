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

/**
 * Same as Kotlin's {@code kotlin.properties.Delegates.vetoable}, but thread safe.
 *
 * (Deviation: upstream is a Kotlin property-delegate function ({@code threadSafeVetoable}) returning a
 * {@code ReadWriteProperty}; Java has no property delegates, so this is a small holder class with
 * {@link #get()}/{@link #set(Object)} instead of a delegated {@code var}.)
 */
public class ThreadSafeVetoable<T>
{
    @FunctionalInterface
    public interface OnChange<T>
    {
        /**
         * @return {@code true} if the change from {@code oldValue} to {@code newValue} should be accepted.
         */
        boolean onChange(T oldValue, T newValue);
    }

    private final Object lock = new Object();
    private final OnChange<T> onChange;
    private volatile T value;

    public ThreadSafeVetoable(T initialValue, OnChange<T> onChange)
    {
        this.value = initialValue;
        this.onChange = onChange;
    }

    public T get()
    {
        return value;
    }

    public void set(T newValue)
    {
        synchronized (lock)
        {
            if (onChange.onChange(value, newValue))
            {
                value = newValue;
            }
        }
    }
}
