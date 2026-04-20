package aff.importer.tool.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * DataStore 扩展属性
 */
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 偏好设置仓库，用于持久化存储用户选择的目录 URI
 */
class PreferencesRepository(private val context: Context) {
    
    companion object {
        private val KEY_DIRECTORY_URI = stringPreferencesKey("directory_uri")
    }
    
    /**
     * 获取已保存的目录 URI
     */
    val savedDirectoryUri: Flow<String?> = context.dataStore.data
        .map { preferences ->
            preferences[KEY_DIRECTORY_URI]
        }
    
    /**
     * 保存目录 URI
     */
    suspend fun saveDirectoryUri(uri: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_DIRECTORY_URI] = uri
        }
    }
    
    /**
     * 清除保存的目录 URI
     */
    suspend fun clearDirectoryUri() {
        context.dataStore.edit { preferences ->
            preferences.remove(KEY_DIRECTORY_URI)
        }
    }
}