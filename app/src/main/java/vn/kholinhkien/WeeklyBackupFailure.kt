package vn.kholinhkien

/** A terminal archive/metadata integrity failure; automatic retries must stop. */
internal class PermanentWeeklyBackupException(message: String) : Exception(message)

/** The authenticated account changed or expired; never continue under another owner. */
internal class WeeklyBackupIdentityException(message: String) : Exception(message)

/** Temporary local-storage pressure; retry only after space becomes available. */
internal class WeeklyBackupStorageException(message: String) : Exception(message)
