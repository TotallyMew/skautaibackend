package lt.skautai.util

import java.io.File

object UploadStorage {
    const val imageUrlPrefix = "/uploads/images"
    const val documentUrlPrefix = "/uploads/documents"

    private val root: File
        get() = File(
            System.getProperty("UPLOADS_DIR")
                ?: System.getenv("UPLOADS_DIR")
                ?: "uploads"
        ).canonicalFile

    fun imagesDir(): File = File(root, "images").canonicalFile

    fun documentsDir(): File = File(root, "documents").canonicalFile

    fun resolveImage(fileName: String): File? = resolve(imagesDir(), fileName)

    fun resolveDocument(fileName: String): File? = resolve(documentsDir(), fileName)

    fun deleteManagedUpload(url: String?, urlPrefix: String) {
        val fileName = url?.takeIf { it.startsWith("$urlPrefix/") }?.removePrefix("$urlPrefix/") ?: return
        val baseDir = when (urlPrefix) {
            imageUrlPrefix -> imagesDir()
            documentUrlPrefix -> documentsDir()
            else -> return
        }
        resolve(baseDir, fileName)?.takeIf { it.exists() }?.delete()
    }

    private fun resolve(baseDir: File, fileName: String): File? {
        if (fileName.isBlank() || fileName.contains("/") || fileName.contains("\\")) return null
        val root = baseDir.canonicalFile
        val candidate = File(root, fileName).canonicalFile
        return if (candidate.toPath().startsWith(root.toPath())) candidate else null
    }
}
