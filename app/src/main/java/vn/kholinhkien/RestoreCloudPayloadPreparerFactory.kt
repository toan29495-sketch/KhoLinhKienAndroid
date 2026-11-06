package vn.kholinhkien

/** Creates the payload preparer with the authenticated Supabase Storage session. */
internal object RestoreCloudPayloadPreparerFactory {
    fun create(dao: InventoryDao): RestoreCloudPayloadPreparer =
        RestoreCloudPayloadPreparer(dao, RestorePhotoUploadFactory.create())
}
