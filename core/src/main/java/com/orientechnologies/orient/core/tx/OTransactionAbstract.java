/*
 * Copyright 2010-2012 Luca Garulli (l.garulli--at--orientechnologies.com)
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
package com.orientechnologies.orient.core.tx;

import com.orientechnologies.common.concur.lock.OLockException;
import com.orientechnologies.common.log.OLogManager;
import com.orientechnologies.orient.core.cache.OLevel1RecordCache;
import com.orientechnologies.orient.core.db.ODatabaseListener;
import com.orientechnologies.orient.core.db.raw.ODatabaseRaw;
import com.orientechnologies.orient.core.db.record.ODatabaseRecordTx;
import com.orientechnologies.orient.core.db.record.OIdentifiable;
import com.orientechnologies.orient.core.db.record.ORecordOperation;
import com.orientechnologies.orient.core.id.ORID;
import com.orientechnologies.orient.core.storage.OStorage;
import com.orientechnologies.orient.core.storage.OStorageEmbedded;

import java.util.HashMap;
import java.util.Map;

public abstract class OTransactionAbstract implements OTransaction {
  protected final ODatabaseRecordTx                  database;
  protected TXSTATUS                                 status = TXSTATUS.INVALID;
  protected HashMap<ORID, OStorage.LOCKING_STRATEGY> locks  = new HashMap<ORID, OStorage.LOCKING_STRATEGY>();

  protected OTransactionAbstract(final ODatabaseRecordTx iDatabase) {
    database = iDatabase;
  }

  public static void updateCacheFromEntries(final OTransaction tx, final Iterable<? extends ORecordOperation> entries,
      final boolean updateStrategy) {
    final OLevel1RecordCache dbCache = tx.getDatabase().getLevel1Cache();

    for (ORecordOperation txEntry : entries) {
      if (!updateStrategy)
        // ALWAYS REMOVE THE RECORD FROM CACHE
        dbCache.deleteRecord(txEntry.getRecord().getIdentity());
      else if (txEntry.type == ORecordOperation.DELETED)
        // DELETION
        dbCache.deleteRecord(txEntry.getRecord().getIdentity());
      else if (txEntry.type == ORecordOperation.UPDATED || txEntry.type == ORecordOperation.CREATED)
        // UDPATE OR CREATE
        dbCache.updateRecord(txEntry.getRecord());
    }
  }

  public boolean isActive() {
    return status != TXSTATUS.INVALID && status != TXSTATUS.COMPLETED && status != TXSTATUS.ROLLED_BACK;
  }

  public TXSTATUS getStatus() {
    return status;
  }

  public ODatabaseRecordTx getDatabase() {
    return database;
  }

  /**
   * Closes the transaction and releases all the acquired locks.
   */
  @Override
  public void close() {
    for (Map.Entry<ORID, OStorage.LOCKING_STRATEGY> lock : locks.entrySet()) {
      try {
        if (lock.getValue().equals(OStorage.LOCKING_STRATEGY.KEEP_EXCLUSIVE_LOCK))
          ((OStorageEmbedded) getDatabase().getStorage()).releaseWriteLock(lock.getKey());
        else if (lock.getValue().equals(OStorage.LOCKING_STRATEGY.KEEP_SHARED_LOCK))
          ((OStorageEmbedded) getDatabase().getStorage()).releaseReadLock(lock.getKey());
      } catch (Exception e) {
        OLogManager.instance().debug(this, "Error on releasing lock against record " + lock.getKey());
      }
    }
    locks.clear();
  }

  @Override
  public OTransaction lockRecord(final OIdentifiable iRecord, final OStorage.LOCKING_STRATEGY iLockingStrategy) {
    final OStorage stg = database.getStorage();
    if (!(stg.getUnderlying() instanceof OStorageEmbedded))
      throw new OLockException("Cannot lock record across remote connections");

    final ORID rid = iRecord.getIdentity();
    // if (locks.containsKey(rid))
    // throw new IllegalStateException("Record " + rid + " is already locked");

    if (iLockingStrategy == OStorage.LOCKING_STRATEGY.KEEP_EXCLUSIVE_LOCK)
      ((OStorageEmbedded) stg).acquireWriteLock(rid);
    else
      ((OStorageEmbedded) stg).acquireReadLock(rid);

    locks.put(rid, iLockingStrategy);
    return this;
  }

  @Override
  public OTransaction unlockRecord(final OIdentifiable iRecord) {
    final OStorage stg = database.getStorage();
    if (!(stg.getUnderlying() instanceof OStorageEmbedded))
      throw new OLockException("Cannot lock record across remote connections");

    final ORID rid = iRecord.getIdentity();

    final OStorage.LOCKING_STRATEGY lock = locks.remove(rid);

    if (lock == null)
      throw new OLockException("Cannot unlock a never acquired lock");
    else if (lock == OStorage.LOCKING_STRATEGY.KEEP_EXCLUSIVE_LOCK)
      ((OStorageEmbedded) stg).releaseWriteLock(rid);
    else
      ((OStorageEmbedded) stg).releaseReadLock(rid);

    return this;
  }

  @Override
  public HashMap<ORID, OStorage.LOCKING_STRATEGY> getLockedRecords() {
    return locks;
  }

  protected void invokeCommitAgainstListeners() {
    // WAKE UP LISTENERS
    for (ODatabaseListener listener : ((ODatabaseRaw) database.getUnderlying()).browseListeners())
      try {
        listener.onBeforeTxCommit(database.getUnderlying());
      } catch (Throwable t) {
        OLogManager.instance().error(this, "Error on commit callback against listener: " + listener, t);
      }
  }

  protected void invokeRollbackAgainstListeners() {
    // WAKE UP LISTENERS
    for (ODatabaseListener listener : ((ODatabaseRaw) database.getUnderlying()).browseListeners())
      try {
        listener.onBeforeTxRollback(database.getUnderlying());
      } catch (Throwable t) {
        OLogManager.instance().error(this, "Error on rollback callback against listener: " + listener, t);
      }
  }
}
