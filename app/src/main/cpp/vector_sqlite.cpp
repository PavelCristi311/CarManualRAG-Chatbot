#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <string>
#include <vector>

#include "sqlite3.h"
#include "sqlite-vec.h"

namespace {
constexpr const char *LOG_TAG = "AtlasVectorSqlite";

struct Candidate {
    double id;
    double distance;
};
}

extern "C"
JNIEXPORT jdoubleArray JNICALL
// Returns alternating chunk IDs and cosine distances for the nearest vectors.
Java_com_atlas_manualassistant_VectorSqliteBridge_nativeSearch(
        JNIEnv *env,
        jclass,
        jstring database_path,
        jbyteArray query_embedding,
        jint limit) {
    if (database_path == nullptr || query_embedding == nullptr || limit <= 0) {
        return env->NewDoubleArray(0);
    }
    const char *path = env->GetStringUTFChars(database_path, nullptr);
    sqlite3 *database = nullptr;
    int status = sqlite3_open_v2(
            path,
            &database,
            SQLITE_OPEN_READONLY | SQLITE_OPEN_NOMUTEX,
            nullptr);
    env->ReleaseStringUTFChars(database_path, path);
    if (status != SQLITE_OK || database == nullptr) {
        __android_log_print(
                ANDROID_LOG_ERROR, LOG_TAG, "open failed: %s",
                database == nullptr ? "null database" : sqlite3_errmsg(database));
        if (database != nullptr) sqlite3_close(database);
        return env->NewDoubleArray(0);
    }
    if (sqlite3_vec_init(database, nullptr, nullptr) != SQLITE_OK) {
        __android_log_print(
                ANDROID_LOG_ERROR, LOG_TAG, "sqlite-vec init failed: %s",
                sqlite3_errmsg(database));
        sqlite3_close(database);
        return env->NewDoubleArray(0);
    }

    static constexpr const char *SQL =
            "SELECT id, vec_distance_cosine(embedding, ?) AS distance "
            "FROM MANUAL_CHUNKS ORDER BY distance LIMIT ?";
    sqlite3_stmt *statement = nullptr;
    if (sqlite3_prepare_v2(database, SQL, -1, &statement, nullptr) != SQLITE_OK) {
        __android_log_print(
                ANDROID_LOG_ERROR, LOG_TAG, "query prepare failed: %s",
                sqlite3_errmsg(database));
        sqlite3_close(database);
        return env->NewDoubleArray(0);
    }

    jsize bytes = env->GetArrayLength(query_embedding);
    jbyte *query = env->GetByteArrayElements(query_embedding, nullptr);
    sqlite3_bind_blob(statement, 1, query, bytes, SQLITE_TRANSIENT);
    sqlite3_bind_int(statement, 2, limit);
    env->ReleaseByteArrayElements(query_embedding, query, JNI_ABORT);

    std::vector<Candidate> candidates;
    candidates.reserve(static_cast<size_t>(limit));
    while (sqlite3_step(statement) == SQLITE_ROW) {
        candidates.push_back({
                static_cast<double>(sqlite3_column_int64(statement, 0)),
                sqlite3_column_double(statement, 1)});
    }
    __android_log_print(
            ANDROID_LOG_DEBUG, LOG_TAG, "returned %zu candidates",
            candidates.size());
    sqlite3_finalize(statement);
    sqlite3_close(database);

    jdoubleArray output = env->NewDoubleArray(
            static_cast<jsize>(candidates.size() * 2));
    if (output == nullptr || candidates.empty()) return output;
    std::vector<jdouble> flattened;
    flattened.reserve(candidates.size() * 2);
    for (const auto &candidate : candidates) {
        flattened.push_back(candidate.id);
        flattened.push_back(candidate.distance);
    }
    env->SetDoubleArrayRegion(
            output, 0, static_cast<jsize>(flattened.size()), flattened.data());
    return output;
}

extern "C"
JNIEXPORT jlongArray JNICALL
// Returns FTS5-ranked chunk IDs for the safely quoted Java query.
Java_com_atlas_manualassistant_VectorSqliteBridge_nativeLexicalSearch(
        JNIEnv *env,
        jclass,
        jstring database_path,
        jstring fts_query,
        jint limit) {
    if (database_path == nullptr || fts_query == nullptr || limit <= 0) {
        return env->NewLongArray(0);
    }
    const char *path = env->GetStringUTFChars(database_path, nullptr);
    sqlite3 *database = nullptr;
    int status = sqlite3_open_v2(
            path,
            &database,
            SQLITE_OPEN_READONLY | SQLITE_OPEN_NOMUTEX,
            nullptr);
    env->ReleaseStringUTFChars(database_path, path);
    if (status != SQLITE_OK || database == nullptr) {
        if (database != nullptr) sqlite3_close(database);
        return env->NewLongArray(0);
    }

    static constexpr const char *SQL =
            "SELECT rowid FROM MANUAL_CHUNKS_FTS "
            "WHERE MANUAL_CHUNKS_FTS MATCH ? "
            "ORDER BY bm25(MANUAL_CHUNKS_FTS, 3.5, 1.0) LIMIT ?";
    sqlite3_stmt *statement = nullptr;
    if (sqlite3_prepare_v2(database, SQL, -1, &statement, nullptr) != SQLITE_OK) {
        __android_log_print(
                ANDROID_LOG_ERROR, LOG_TAG, "FTS5 query prepare failed: %s",
                sqlite3_errmsg(database));
        sqlite3_close(database);
        return env->NewLongArray(0);
    }
    const char *query = env->GetStringUTFChars(fts_query, nullptr);
    sqlite3_bind_text(statement, 1, query, -1, SQLITE_TRANSIENT);
    sqlite3_bind_int(statement, 2, limit);
    env->ReleaseStringUTFChars(fts_query, query);

    std::vector<jlong> ids;
    ids.reserve(static_cast<size_t>(limit));
    while (sqlite3_step(statement) == SQLITE_ROW) {
        ids.push_back(static_cast<jlong>(sqlite3_column_int64(statement, 0)));
    }
    sqlite3_finalize(statement);
    sqlite3_close(database);

    __android_log_print(
            ANDROID_LOG_DEBUG, LOG_TAG, "returned %zu FTS5 candidates",
            ids.size());
    jlongArray output = env->NewLongArray(static_cast<jsize>(ids.size()));
    if (output != nullptr && !ids.empty()) {
        env->SetLongArrayRegion(
                output, 0, static_cast<jsize>(ids.size()), ids.data());
    }
    return output;
}
