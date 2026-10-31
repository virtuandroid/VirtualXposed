package com.lody.virtual.server.permission

import com.lody.virtual.helper.utils.VLog
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.SAXException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.Transformer
import javax.xml.transform.TransformerException
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * @author Alberto Lazari
 */
internal class PermissionFileParser {
    private val documentBuilder: DocumentBuilder
    private val transformer: Transformer

    init {
        try {
            documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            transformer = TransformerFactory.newInstance().newTransformer()
            transformer.setOutputProperty(OutputKeys.DOCTYPE_PUBLIC, "yes")
            transformer.setOutputProperty(OutputKeys.INDENT, "yes")
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
        } catch (_: Exception) {
            throw RuntimeException("Could not create PermissionFileParser")
        }
    }

    /**
     * Read permissions list for all UIDs from permissions file
     */
    @Throws(IOException::class, SAXException::class)
    fun read(fileStream: InputStream): MutableMap<Int, AppPermissions> {
        val permissions = HashMap<Int, AppPermissions>()
        val bytes = ByteArray(fileStream.available())
        fileStream.read(bytes)
        val byteStream = ByteArrayInputStream(bytes)
        // Parse from byte stream to avoid automatically closing the file stream
        val doc = documentBuilder.parse(byteStream)

        val appsList = doc.getElementsByTagName("app")
        for (i in 0..<appsList.length) {
            val appElement = appsList.item(i) as Element
            val uid = appElement.getAttribute("uid").toInt()

            val appPermissions = HashMap<String?, Permission>()
            fillPermissions(PermissionGroup::class.java, appPermissions, appElement)
            fillPermissions(InstallPermission::class.java, appPermissions, appElement)
            fillPermissions(RuntimePermission::class.java, appPermissions, appElement)

            permissions[uid] = AppPermissions(appPermissions)
        }
        return permissions
    }

    /**
     * Write permissions list for all UIDs to permissions file
     */
    @Throws(IOException::class, TransformerException::class)
    fun write(cache: MutableMap<Int, AppPermissions>?, fileStream: OutputStream) {
        val doc = documentBuilder.newDocument()
        val root = doc.createElement("permissions")
        doc.appendChild(root)
        // Iterate through installed virtual apps
        cache?.forEach { (uid: Int?, appPermissions: AppPermissions?) ->
            val appElement = doc.createElement("app")
            root.appendChild(appElement)
            appElement.setAttribute("uid", uid.toString())
            TYPE_TO_TAG_MAP.keys.forEach { type: Class<out Permission?>? ->
                addPermissions(type, appPermissions!!, doc, appElement)
            }
        }
        // Write to byte stream to avoid closing the file stream
        val byteStream = ByteArrayOutputStream()
        transformer.transform(DOMSource(doc), StreamResult(byteStream))
        // Write XML to file
        byteStream.writeTo(fileStream)
    }

    /**
     * Log permission file contents
     */
    @Throws(IOException::class, SAXException::class, TransformerException::class)
    fun log(fileStream: InputStream) {
        val bytes = ByteArray(fileStream.available())
        fileStream.read(bytes)
        val byteStream = ByteArrayInputStream(bytes)
        // Parse from byte stream to avoid automatically closing the file stream
        val doc = documentBuilder.parse(byteStream)
        val writer = StringWriter()
        transformer.transform(DOMSource(doc), StreamResult(writer))
        VLog.d(TAG, "permissions.xml file content:")
        VLog.d(TAG, writer.toString())
    }

    companion object {
        private val TAG: String = PermissionFileParser::class.java.getSimpleName()

        val TYPE_TO_TAG_MAP: Map<Class<out Permission>, String> =
            mapOf(
                InstallPermission::class.java to "install-permissions",
                RuntimePermission::class.java to "runtime-permissions",
                PermissionGroup::class.java to "permission-groups"
            )

        /**
         * Subroutine for `read()`
         * @return @type permissions
         */
        private fun fillPermissions(
            type: Class<out Permission?>?,
            permissions: MutableMap<String?, Permission>, appElement: Element
        ) {
            val tagName: String? = TYPE_TO_TAG_MAP[type]
            val permissionNodeList = appElement.getElementsByTagName(tagName)
            // Iterate through all permission type tags (should be one)
            for (i in 0..<permissionNodeList.length) {
                val permissionsElement = permissionNodeList.item(i) as Element
                val permissionList = permissionsElement.getElementsByTagName("permission")
                // Get all <permission> tags
                for (j in 0..<permissionList.length) {
                    val permissionElement = permissionList.item(j) as Element
                    val name = permissionElement.getAttribute("name")
                    val status = permissionElement.getAttribute("status")
                    val permission = when (tagName) {
                        "install-permissions" -> InstallPermission(name, status)
                        "runtime-permissions" -> {
                            val groupPermission = permissions[permissionElement.getAttribute("group")]
                            if (groupPermission != null
                                && groupPermission is PermissionGroup
                            ) {
                                val runtimePermission =
                                    RuntimePermission(name, groupPermission, status)
                                groupPermission.addPermission(runtimePermission)
                            }
                            RuntimePermission(name, null, status)
                        }

                        "permission-groups" -> PermissionGroup(name, status)
                        else -> throw RuntimeException("Unsupported tag name: $tagName")
                    }
                    permissions[name] = permission
                }
            }
        }


        /**
         * Subroutine for `write()`
         * Add @type @permissions to a @permissionsElement
         */
        private fun addPermissions(
            type: Class<out Permission?>?,
            appPermissions: AppPermissions, doc: Document, appElement: Element
        ) {
            val tagName: String? = TYPE_TO_TAG_MAP[type]
            val permissionsElement = doc.createElement(tagName)
            appElement.appendChild(permissionsElement)
            appPermissions.all.forEach { (permissionName: String?, permission: Permission?) ->
                if (type?.isInstance(permission) == false) {
                    return@forEach
                }

                val permissionElement = doc.createElement("permission")
                permissionsElement.appendChild(permissionElement)
                permissionElement.setAttribute("name", permissionName)
                if (permission is RuntimePermission) {
                    if (permission.hasPermissionGroup()) {
                        val group = permission.permissionGroup
                        permissionElement.setAttribute("group", group!!.name)
                    }
                }
                permissionElement.setAttribute("status", permission!!.statusToString())
            }
        }
    }
}
