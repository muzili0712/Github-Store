package zed.rainxch.core.data.local.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds per-app preferred-variant tracking to the installed_apps table:
 *  - preferredAssetVariant: stable identifier (e.g. "arm64-v8a") for the
 *    asset the user wants to install. Survives version bumps because it's
 *    derived from the part of the filename that doesn't change.
 *  - preferredVariantStale: flipped to true by checkForUpdates when the
 *    persisted variant cannot be matched in a fresh release; the UI then
 *    prompts the user to pick again.
 *
 * Both columns default to safe "no preference" values so existing rows
 * keep their current auto-pick behaviour.
 */
val MIGRATION_10_11 =
    object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE installed_apps ADD COLUMN preferredAssetVariant TEXT")
            db.execSQL(
                "ALTER TABLE installed_apps ADD COLUMN preferredVariantStale INTEGER NOT NULL DEFAULT 0",
            )
        }
    }
