/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.stream.kafka;

import kafka.producer.Partitioner;
import kafka.utils.VerifiableProperties;

/**
 * Simple partitioner for Kafka.
 */
@SuppressWarnings("UnusedDeclaration")
public class SimplePartitioner implements Partitioner {
    /**
     * Constructs instance.
     *
     * @param props Properties.
     */
    public SimplePartitioner(VerifiableProperties props) {
        // No-op.
    }

    /**
     * Partitions the key based on the key value.
     *
     * @param key Key.
     * @param partSize Partition size.
     * @return partition Partition.
     */
    public int partition(Object key, int partSize) {
        String keyStr = (String)key;

        String[] keyValues = keyStr.split("\\.");

        Integer intKey = Integer.parseInt(keyValues[3]);

        return intKey > 0 ? intKey % partSize : 0;
    }
}