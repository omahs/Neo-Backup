/*
 * OAndBackupX: open-source apps backup and restore app.
 * Copyright (C) 2020  Antonios Hazim
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.machiav3lli.backup.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.machiav3lli.backup.OABX
import com.machiav3lli.backup.PACKAGES_LIST_GLOBAL_ID
import com.machiav3lli.backup.dbs.ODatabase
import com.machiav3lli.backup.dbs.entity.AppExtras
import com.machiav3lli.backup.dbs.entity.AppInfo
import com.machiav3lli.backup.dbs.entity.Backup
import com.machiav3lli.backup.dbs.entity.Blocklist
import com.machiav3lli.backup.handler.toPackageList
import com.machiav3lli.backup.handler.updateAppTables
import com.machiav3lli.backup.items.Package
import com.machiav3lli.backup.items.Package.Companion.invalidateCacheForPackage
import com.machiav3lli.backup.preferences.pref_usePackageCacheOnUpdate
import com.machiav3lli.backup.ui.compose.MutableComposableSharedFlow
import com.machiav3lli.backup.utils.applyFilter
import com.machiav3lli.backup.utils.sortFilterModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.reflect.*
import kotlin.system.measureTimeMillis

class MainViewModel(
    private val db: ODatabase,
    private val appContext: Application
) : AndroidViewModel(appContext) {

    // TODO consider adding option for tracing

    val blocklist = db.blocklistDao.allFlow

        .onEach { Timber.w("*** blocklist <<- ${it.size}") }
        .stateIn(
            viewModelScope,
            SharingStarted.Lazily,
            emptyList()
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    val backupsMap = db.backupDao.allFlow

        .mapLatest { it.groupBy(Backup::packageName) }
        .onEach { Timber.w("*** backupsMap <<- ${it.size}") }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            emptyMap()
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    val appExtrasMap = db.appExtrasDao.allFlow

        .mapLatest { it.associateBy(AppExtras::packageName) }
        .onEach { Timber.w("*** appExtrasMap <<- ${it.size}") }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            emptyMap()
        )

    val packageList = combine(db.appInfoDao.allFlow, backupsMap) { p, b ->

        Timber.w("******************** database - db: ${p.size} backups: ${b.size}")

        val list =
            p.toPackageList(
                appContext,
                emptyList(),
                b
            )

        Timber.w("***** packages ->> ${list.size}")
        list
    }   .onEach { Timber.w("*** packageList <<- ${it.size}") }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            emptyList()
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    val packageMap = packageList
        .mapLatest { it.associateBy(Package::packageName) }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            emptyMap()
        )

    val notBlockedList = combine(packageList, blocklist) { p, b ->

        Timber.w(
            "******************** blocking - list: ${p.size} block: ${
                b.joinToString(
                    ","
                )
            }"
        )

        val block = b.map { it.packageName }
        val list = p.filterNot { block.contains(it.packageName) }

        Timber.w("***** blocked ->> ${list.size}")
        list
    }   .onEach { Timber.w("*** notBlockedList <<- ${it.size}") }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            emptyList()
        )

    val searchQuery = MutableComposableSharedFlow("", viewModelScope, "searchQuery") {
        Timber.w("*** searchQuery <<- '${it}'")
    }

    var modelSortFilter = MutableComposableSharedFlow(
        OABX.context.sortFilterModel,
        viewModelScope,
        "modelSortFilter"
    ) {
        //Timber.w("*** modelSortFilter <<- ${it}")
    }

    val filteredList = combine(notBlockedList, modelSortFilter.flow, searchQuery.flow) { p, f, s ->

        Timber.w("******************** filtering - list: ${p.size} filter: $f")

        val list = p
            .filter { item: Package ->
                s.isEmpty() || (
                        listOf(item.packageName, item.packageLabel)
                            .any { it.contains(s, ignoreCase = true) }
                        )
            }
            .applyFilter(f, OABX.main!!)

        Timber.w("***** filtered ->> ${list.size}")
        list
    }   .onEach { Timber.w("*** filteredList <<- ${it.size}") }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            emptyList()
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    val updatedPackages = notBlockedList
        .mapLatest { it.filter(Package::isUpdated).toMutableList() }
        .onEach { Timber.w("*** updatedPackages <<- ${it.size}") }


        
    // TODO add to interface
    fun refreshList() {
        viewModelScope.launch {
            recreateAppInfoList()
        }
    }

    private suspend fun recreateAppInfoList() {
        withContext(Dispatchers.IO) {
            OABX.beginBusy("recreateAppInfoList")
            val time = measureTimeMillis {
                appContext.updateAppTables(db.appInfoDao, db.backupDao)
            }
            OABX.addInfoText("recreateAppInfoList: ${(time / 1000 + 0.5).toInt()} sec")
            OABX.endBusy("recreateAppInfoList")
        }
    }

    fun updatePackage(packageName: String) {
        viewModelScope.launch {
            packageMap.value[packageName]?.let {
                updateDataOf(packageName)
            }
        }
    }

    private suspend fun updateDataOf(packageName: String) =
        withContext(Dispatchers.IO) {
            OABX.beginBusy("updateDataOf")
            invalidateCacheForPackage(packageName)
            val appPackage = packageMap.value[packageName]
            try {
                appPackage?.apply {
                    if (pref_usePackageCacheOnUpdate.value) {
                        val new = Package.get(packageName) {
                            Package(appContext, packageName, getAppBackupRoot())
                        }
                        new.ensureBackupList()
                        new.refreshFromPackageManager(OABX.context)
                        new.refreshStorageStats(OABX.context)
                        if (!isSpecial) db.appInfoDao.update(new.packageInfo as AppInfo)
                        db.backupDao.updateList(new)
                    } else {
                        val new = Package(appContext, packageName, getAppBackupRoot())
                        new.refreshFromPackageManager(OABX.context)
                        if (!isSpecial) db.appInfoDao.update(new.packageInfo as AppInfo)
                        db.backupDao.updateList(new)
                    }
                }
            } catch (e: AssertionError) {
                Timber.w(e.message ?: "")
            }
            OABX.endBusy("updateDataOf")
        }

    fun updateExtras(appExtras: AppExtras) {
        viewModelScope.launch {
            updateExtrasWith(appExtras)
        }
    }

    private suspend fun updateExtrasWith(appExtras: AppExtras) {
        withContext(Dispatchers.IO) {
            val oldExtras = db.appExtrasDao.all.find { it.packageName == appExtras.packageName }
            if (oldExtras != null) {
                appExtras.id = oldExtras.id
                db.appExtrasDao.update(appExtras)
            } else
                db.appExtrasDao.insert(appExtras)
            true
        }
    }

    fun setExtras(appExtras: Map<String, AppExtras>) {
        viewModelScope.launch { replaceExtras(appExtras.values) }
    }

    private suspend fun replaceExtras(appExtras: Collection<AppExtras>) {
        withContext(Dispatchers.IO) {
            db.appExtrasDao.deleteAll()
            db.appExtrasDao.insert(*appExtras.toTypedArray())
        }
    }

    fun addToBlocklist(packageName: String) {
        viewModelScope.launch {
            insertIntoBlocklistDB(packageName)
        }
    }

    //fun removeFromBlocklist(packageName: String) {
    //    viewModelScope.launch {
    //        removeFromBlocklistDB(packageName)
    //    }
    //}

    private suspend fun insertIntoBlocklistDB(packageName: String) {
        withContext(Dispatchers.IO) {
            db.blocklistDao.insert(
                Blocklist.Builder()
                    .withId(0)
                    .withBlocklistId(PACKAGES_LIST_GLOBAL_ID)
                    .withPackageName(packageName)
                    .build()
            )
        }
    }

    //private suspend fun removeFromBlocklistDB(packageName: String) {
    //    updateBlocklist(
    //        (blocklist.value
    //            ?.map { it.packageName }
    //            ?.filterNotNull()
    //            ?.filterNot { it == packageName }
    //            ?: listOf()
    //        ).toSet()
    //    )
    //}

    fun setBlocklist(newList: Set<String>) {
        viewModelScope.launch {
            insertIntoBlocklistDB(newList)
        }
    }

    private suspend fun insertIntoBlocklistDB(newList: Set<String>) =
        withContext(Dispatchers.IO) {
            db.blocklistDao.updateList(PACKAGES_LIST_GLOBAL_ID, newList)
        }

    class Factory(
        private val database: ODatabase,
        private val application: Application
    ) : ViewModelProvider.Factory {
        @Suppress("unchecked_cast")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                return MainViewModel(database, application) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}

