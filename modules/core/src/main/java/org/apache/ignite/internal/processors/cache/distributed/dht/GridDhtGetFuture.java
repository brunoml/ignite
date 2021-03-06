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

package org.apache.ignite.internal.processors.cache.distributed.dht;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.ignite.IgniteCheckedException;
import org.apache.ignite.IgniteLogger;
import org.apache.ignite.cluster.ClusterNode;
import org.apache.ignite.internal.IgniteInternalFuture;
import org.apache.ignite.internal.processors.affinity.AffinityTopologyVersion;
import org.apache.ignite.internal.processors.cache.CacheObject;
import org.apache.ignite.internal.processors.cache.GridCacheContext;
import org.apache.ignite.internal.processors.cache.GridCacheEntryInfo;
import org.apache.ignite.internal.processors.cache.GridCacheEntryRemovedException;
import org.apache.ignite.internal.processors.cache.IgniteCacheExpiryPolicy;
import org.apache.ignite.internal.processors.cache.KeyCacheObject;
import org.apache.ignite.internal.processors.cache.transactions.IgniteTxLocalEx;
import org.apache.ignite.internal.processors.cache.version.GridCacheVersion;
import org.apache.ignite.internal.util.future.GridCompoundFuture;
import org.apache.ignite.internal.util.future.GridCompoundIdentityFuture;
import org.apache.ignite.internal.util.future.GridEmbeddedFuture;
import org.apache.ignite.internal.util.future.GridFinishedFuture;
import org.apache.ignite.internal.util.lang.GridClosureException;
import org.apache.ignite.internal.util.typedef.C2;
import org.apache.ignite.internal.util.typedef.F;
import org.apache.ignite.internal.util.typedef.T2;
import org.apache.ignite.internal.util.typedef.internal.CU;
import org.apache.ignite.internal.util.typedef.internal.U;
import org.apache.ignite.lang.IgniteBiClosure;
import org.apache.ignite.lang.IgniteUuid;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 *
 */
public final class GridDhtGetFuture<K, V> extends GridCompoundIdentityFuture<Collection<GridCacheEntryInfo>>
    implements GridDhtFuture<Collection<GridCacheEntryInfo>> {
    /** */
    private static final long serialVersionUID = 0L;

    /** Logger reference. */
    private static final AtomicReference<IgniteLogger> logRef = new AtomicReference<>();

    /** Logger. */
    private static IgniteLogger log;

    /** Message ID. */
    private long msgId;

    /** */
    private UUID reader;

    /** Read through flag. */
    private boolean readThrough;

    /** Context. */
    private GridCacheContext<K, V> cctx;

    /** Keys. */
    private Map<KeyCacheObject, Boolean> keys;

    /** Reserved partitions. */
    private Collection<GridDhtLocalPartition> parts = new HashSet<>();

    /** Future ID. */
    private IgniteUuid futId;

    /** Version. */
    private GridCacheVersion ver;

    /** Topology version .*/
    private AffinityTopologyVersion topVer;

    /** Transaction. */
    private IgniteTxLocalEx tx;

    /** Retries because ownership changed. */
    private Collection<Integer> retries;

    /** Subject ID. */
    private UUID subjId;

    /** Task name. */
    private int taskNameHash;

    /** Expiry policy. */
    private IgniteCacheExpiryPolicy expiryPlc;

    /** Skip values flag. */
    private boolean skipVals;

    /**
     * @param cctx Context.
     * @param msgId Message ID.
     * @param reader Reader.
     * @param keys Keys.
     * @param readThrough Read through flag.
     * @param tx Transaction.
     * @param topVer Topology version.
     * @param subjId Subject ID.
     * @param taskNameHash Task name hash code.
     * @param expiryPlc Expiry policy.
     * @param skipVals Skip values flag.
     */
    public GridDhtGetFuture(
        GridCacheContext<K, V> cctx,
        long msgId,
        UUID reader,
        Map<KeyCacheObject, Boolean> keys,
        boolean readThrough,
        @Nullable IgniteTxLocalEx tx,
        @NotNull AffinityTopologyVersion topVer,
        @Nullable UUID subjId,
        int taskNameHash,
        @Nullable IgniteCacheExpiryPolicy expiryPlc,
        boolean skipVals
    ) {
        super(cctx.kernalContext(), CU.<GridCacheEntryInfo>collectionsReducer());

        assert reader != null;
        assert !F.isEmpty(keys);

        this.reader = reader;
        this.cctx = cctx;
        this.msgId = msgId;
        this.keys = keys;
        this.readThrough = readThrough;
        this.tx = tx;
        this.topVer = topVer;
        this.subjId = subjId;
        this.taskNameHash = taskNameHash;
        this.expiryPlc = expiryPlc;
        this.skipVals = skipVals;

        futId = IgniteUuid.randomUuid();

        ver = tx == null ? cctx.versions().next() : tx.xidVersion();

        if (log == null)
            log = U.logger(cctx.kernalContext(), logRef, GridDhtGetFuture.class);
    }

    /**
     * Initializes future.
     */
    void init() {
        map(keys);

        markInitialized();
    }

    /** {@inheritDoc} */
    @Override public Collection<Integer> invalidPartitions() {
        return retries == null ? Collections.<Integer>emptyList() : retries;
    }

    /**
     * @return Future ID.
     */
    public IgniteUuid futureId() {
        return futId;
    }

    /**
     * @return Future version.
     */
    public GridCacheVersion version() {
        return ver;
    }

    /** {@inheritDoc} */
    @Override public boolean onDone(Collection<GridCacheEntryInfo> res, Throwable err) {
        if (super.onDone(res, err)) {
            // Release all partitions reserved by this future.
            for (GridDhtLocalPartition part : parts)
                part.release();

            return true;
        }

        return false;
    }

    /**
     * @param keys Keys.
     */
    private void map(final Map<KeyCacheObject, Boolean> keys) {
        GridDhtFuture<Object> fut = cctx.dht().dhtPreloader().request(keys.keySet(), topVer);

        if (!F.isEmpty(fut.invalidPartitions())) {
            if (retries == null)
                retries = new HashSet<>();

            retries.addAll(fut.invalidPartitions());
        }

        add(new GridEmbeddedFuture<>(
            new IgniteBiClosure<Object, Exception, Collection<GridCacheEntryInfo>>() {
                @Override public Collection<GridCacheEntryInfo> apply(Object o, Exception e) {
                    if (e != null) { // Check error first.
                        if (log.isDebugEnabled())
                            log.debug("Failed to request keys from preloader [keys=" + keys + ", err=" + e + ']');

                        onDone(e);
                    }

                    Map<KeyCacheObject, Boolean> mappedKeys = null;

                    // Assign keys to primary nodes.
                    for (Map.Entry<KeyCacheObject, Boolean> key : keys.entrySet()) {
                        int part = cctx.affinity().partition(key.getKey());

                        if (retries == null || !retries.contains(part)) {
                            if (!map(key.getKey(), parts)) {
                                if (retries == null)
                                    retries = new HashSet<>();

                                retries.add(part);

                                if (mappedKeys == null) {
                                    mappedKeys = U.newLinkedHashMap(keys.size());

                                    for (Map.Entry<KeyCacheObject, Boolean> key1 : keys.entrySet()) {
                                        if (key1.getKey() == key.getKey())
                                            break;

                                        mappedKeys.put(key.getKey(), key1.getValue());
                                    }
                                }
                            }
                            else if (mappedKeys != null)
                                mappedKeys.put(key.getKey(), key.getValue());
                        }
                    }

                    // Add new future.
                    add(getAsync(mappedKeys == null ? keys : mappedKeys));

                    // Finish this one.
                    return Collections.emptyList();
                }
            },
            fut));
    }

    /**
     * @param key Key.
     * @param parts Parts to map.
     * @return {@code True} if mapped.
     */
    private boolean map(KeyCacheObject key, Collection<GridDhtLocalPartition> parts) {
        GridDhtLocalPartition part = topVer.topologyVersion() > 0 ?
            cache().topology().localPartition(cctx.affinity().partition(key), topVer, true) :
            cache().topology().localPartition(key, false);

        if (part == null)
            return false;

        if (!parts.contains(part)) {
            // By reserving, we make sure that partition won't be unloaded while processed.
            if (part.reserve()) {
                parts.add(part);

                return true;
            }
            else
                return false;
        }
        else
            return true;
    }

    /**
     * @param keys Keys to get.
     * @return Future for local get.
     */
    @SuppressWarnings( {"unchecked", "IfMayBeConditional"})
    private IgniteInternalFuture<Collection<GridCacheEntryInfo>> getAsync(
        final Map<KeyCacheObject, Boolean> keys
    ) {
        if (F.isEmpty(keys))
            return new GridFinishedFuture<Collection<GridCacheEntryInfo>>(
                Collections.<GridCacheEntryInfo>emptyList());

        String taskName0 = cctx.kernalContext().job().currentTaskName();

        if (taskName0 == null)
            taskName0 = cctx.kernalContext().task().resolveTaskName(taskNameHash);

        final String taskName = taskName0;

        GridCompoundFuture<Boolean, Boolean> txFut = null;

        ClusterNode readerNode = cctx.discovery().node(reader);

        if (readerNode != null && !readerNode.isLocal() && cctx.discovery().cacheNearNode(readerNode, cctx.name())) {
            for (Map.Entry<KeyCacheObject, Boolean> k : keys.entrySet()) {
                while (true) {
                    GridDhtCacheEntry e = cache().entryExx(k.getKey(), topVer);

                    try {
                        if (e.obsolete())
                            continue;

                        boolean addReader = (!e.deleted() && k.getValue() && !skipVals);

                        if (addReader)
                            e.unswap(false);

                        // Register reader. If there are active transactions for this entry,
                        // then will wait for their completion before proceeding.
                        // TODO: GG-4003:
                        // TODO: What if any transaction we wait for actually removes this entry?
                        // TODO: In this case seems like we will be stuck with untracked near entry.
                        // TODO: To fix, check that reader is contained in the list of readers once
                        // TODO: again after the returned future completes - if not, try again.
                        IgniteInternalFuture<Boolean> f = addReader ? e.addReader(reader, msgId, topVer) : null;

                        if (f != null) {
                            if (txFut == null)
                                txFut = new GridCompoundFuture<>(CU.boolReducer());

                            txFut.add(f);
                        }

                        break;
                    }
                    catch (IgniteCheckedException err) {
                        return new GridFinishedFuture<>(err);
                    }
                    catch (GridCacheEntryRemovedException ignore) {
                        if (log.isDebugEnabled())
                            log.debug("Got removed entry when getting a DHT value: " + e);
                    }
                    finally {
                        cctx.evicts().touch(e, topVer);
                    }
                }
            }

            if (txFut != null)
                txFut.markInitialized();
        }

        IgniteInternalFuture<Map<KeyCacheObject, T2<CacheObject, GridCacheVersion>>> fut;

        if (txFut == null || txFut.isDone()) {
            if (tx == null) {
                fut = cache().getDhtAllAsync(
                    keys.keySet(),
                    readThrough,
                    subjId,
                    taskName,
                    expiryPlc,
                    skipVals,
                    /*can remap*/true);
            }
            else {
                fut = tx.getAllAsync(cctx,
                    keys.keySet(),
                    /*deserialize binary*/false,
                    skipVals,
                    /*keep cache objects*/true,
                    /*skip store*/!readThrough);
            }
        }
        else {
            // If we are here, then there were active transactions for some entries
            // when we were adding the reader. In that case we must wait for those
            // transactions to complete.
            fut = new GridEmbeddedFuture<>(
                txFut,
                new C2<Boolean, Exception, IgniteInternalFuture<Map<KeyCacheObject, T2<CacheObject, GridCacheVersion>>>>() {
                    @Override public IgniteInternalFuture<Map<KeyCacheObject, T2<CacheObject, GridCacheVersion>>> apply(Boolean b, Exception e) {
                        if (e != null)
                            throw new GridClosureException(e);

                        if (tx == null) {
                            return cache().getDhtAllAsync(
                                keys.keySet(),
                                readThrough,
                                subjId,
                                taskName,
                                expiryPlc,
                                skipVals,
                                /*can remap*/true);
                        }
                        else {
                            return tx.getAllAsync(cctx,
                                keys.keySet(),
                                /*deserialize binary*/false,
                                skipVals,
                                /*keep cache objects*/true,
                                /*skip store*/!readThrough);
                        }
                    }
                }
            );
        }

        return new GridEmbeddedFuture<>(
            new C2<Map<KeyCacheObject, T2<CacheObject, GridCacheVersion>>, Exception, Collection<GridCacheEntryInfo>>() {
                @Override public Collection<GridCacheEntryInfo> apply(Map<KeyCacheObject, T2<CacheObject, GridCacheVersion>> map, Exception e) {
                    if (e != null) {
                        onDone(e);

                        return Collections.emptyList();
                    }
                    else {
                        Collection<GridCacheEntryInfo> infos = new ArrayList<>(map.size());

                        for (Map.Entry<KeyCacheObject, T2<CacheObject, GridCacheVersion>> entry : map.entrySet()) {
                            T2<CacheObject, GridCacheVersion> val = entry.getValue();

                            assert val != null;

                            GridCacheEntryInfo info = new GridCacheEntryInfo();

                            info.cacheId(cctx.cacheId());
                            info.key(entry.getKey());
                            info.value(skipVals ? null : val.get1());
                            info.version(val.get2());

                            infos.add(info);
                        }

                        return infos;
                    }
                }
            },
            fut);
    }

    /**
     * @return DHT cache.
     */
    private GridDhtCacheAdapter<K, V> cache() {
        return (GridDhtCacheAdapter<K, V>)cctx.cache();
    }
}
