package com.lody.virtual.server.permission;

import com.lody.virtual.helper.utils.VLog;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * @author Alberto Lazari
 */
class PermissionFileParser {

    private static final String TAG = PermissionFileParser.class.getSimpleName();

    public static final Map<Class<? extends Permission>, String> TYPE_TO_TAG_MAP = Map.of(
        InstallPermission.class, "install-permissions",
        RuntimePermission.class, "runtime-permissions",
        PermissionGroup.class, "permission-groups"
    );

    private final DocumentBuilder documentBuilder;
    private final Transformer transformer;

    public PermissionFileParser() {
        try {
            documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.DOCTYPE_PUBLIC,"yes");
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        } catch (Exception e) {
            throw new RuntimeException("Could not create PermissionFileParser");
        }
    }

    /**
     * Read permissions list for all UIDs from permissions file
     */
    public Map<Integer, AppPermissions> read(final InputStream fileStream)
            throws IOException, SAXException {

        final var permissions = new HashMap<Integer, AppPermissions>();
        final var bytes = new byte[fileStream.available()];
        fileStream.read(bytes);
        final var byteStream = new ByteArrayInputStream(bytes);
        // Parse from byte stream to avoid automatically closing the file stream
        final var doc = documentBuilder.parse(byteStream);

        final NodeList appsList = doc.getElementsByTagName("app");
        for (int i = 0; i < appsList.getLength(); ++i) {
            final var appElement = (Element) appsList.item(i);
            final int uid = Integer.parseInt(appElement.getAttribute("uid"));

            final var appPermissions = new HashMap<String, Permission>();
            fillPermissions(PermissionGroup.class, appPermissions, appElement);
            fillPermissions(InstallPermission.class, appPermissions, appElement);
            fillPermissions(RuntimePermission.class, appPermissions, appElement);

            permissions.put(uid, new AppPermissions(appPermissions));
        }
        return permissions;
    }

    /**
     * Subroutine for `read()`
     * @return @type permissions
     */
    private static void fillPermissions(final Class<? extends Permission> type,
            final Map<String, Permission> permissions, final Element appElement) {

        final var tagName = TYPE_TO_TAG_MAP.get(type);
        final var permissionNodeList = appElement.getElementsByTagName(tagName);
        // Iterate through all permission type tags (should be one)
        for (int i = 0; i < permissionNodeList.getLength(); ++i) {
            final var permissionsElement = (Element) permissionNodeList.item(i);
            final var permissionList = permissionsElement.getElementsByTagName("permission");
            // Get all <permission> tags
            for (int j = 0; j < permissionList.getLength(); ++j) {
                final var permissionElement = (Element) permissionList.item(j);
                final var name = permissionElement.getAttribute("name");
                final var status = permissionElement.getAttribute("status");
                final Permission permission = switch (tagName) {
                    case "install-permissions" -> new InstallPermission(name, status);
                    case "runtime-permissions" -> {
                        final var groupPermission = permissions.get(
                                permissionElement.getAttribute("group"));
                        if (groupPermission != null
                                && groupPermission instanceof PermissionGroup group) {
                            final var runtimePermission = new RuntimePermission(name, group, status);
                            group.addPermission(runtimePermission);
                            yield runtimePermission;
                        }
                        yield new RuntimePermission(name, null, status);
                    }
                    case "permission-groups" -> new PermissionGroup(name, status);
                    default -> throw new RuntimeException("Unsupported tag name: " + tagName);
                };
                permissions.put(name, permission);
            }
        }
    }


    /**
     * Write permissions list for all UIDs to permissions file
     */
    public void write(final Map<Integer, AppPermissions> cache, final OutputStream fileStream)
            throws IOException, TransformerException {

        final var doc = documentBuilder.newDocument();
        final var root = doc.createElement("permissions");
        doc.appendChild(root);
        // Iterate through installed virtual apps
        cache.forEach((uid, appPermissions) -> {
            final var appElement = doc.createElement("app");
            root.appendChild(appElement);
            appElement.setAttribute("uid", uid.toString());
            TYPE_TO_TAG_MAP.keySet().forEach(type -> {
                addPermissions(type, appPermissions, doc, appElement);
            });
        });
        // Write to byte stream to avoid closing the file stream
        final ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        transformer.transform(new DOMSource(doc), new StreamResult(byteStream));
        // Write XML to file
        byteStream.writeTo(fileStream);
    }

    /**
     * Subroutine for `write()`
     * Add @type @permissions to a @permissionsElement
     */
    private static void addPermissions(final Class<? extends Permission> type,
            final AppPermissions appPermissions, final Document doc, final Element appElement) {

        final var tagName = TYPE_TO_TAG_MAP.get(type);
        final var permissionsElement = doc.createElement(tagName);
        appElement.appendChild(permissionsElement);
        appPermissions.getAll(type).forEach((permissionName, permission) -> {
            final var permissionElement = doc.createElement("permission");
            permissionsElement.appendChild(permissionElement);
            permissionElement.setAttribute("name", permissionName);
            if (permission instanceof RuntimePermission runtimePermission) {
                if (runtimePermission.hasPermissionGroup()) {
                    final var group = runtimePermission.getPermissionGroup();
                    permissionElement.setAttribute("group", group.getName());
                }
            }
            permissionElement.setAttribute("status", permission.statusToString());
        });
    }


    /**
     * Log permission file contents
     */
    public void log(final InputStream fileStream)
            throws IOException, SAXException, TransformerException {

        var bytes = new byte[fileStream.available()];
        fileStream.read(bytes);
        final var byteStream = new ByteArrayInputStream(bytes);
        // Parse from byte stream to avoid automatically closing the file stream
        final var doc = documentBuilder.parse(byteStream);
        final var writer = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(writer));
        VLog.d(TAG, "permissions.xml file content:");
        VLog.d(TAG, writer.toString());
    }
}
