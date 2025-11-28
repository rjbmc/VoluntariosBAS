package util.sevilla.bancodealimentos.es;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import com.microsoft.graph.models.FieldValueSet;
import com.microsoft.graph.models.ListItem;
import com.microsoft.graph.models.ListItemCollectionResponse;

public final class SharepointReplicationUtil {

    public enum Operation {
        INSERT,
        UPDATE,
        DELETE
    }

    private static final String SQL_UUID_FIELD = "SqlRowUUID";

    private SharepointReplicationUtil() { }

    public static String generateUuid() {
        return UUID.randomUUID().toString();
    }

    public static void replicate(Connection conn, String targetSiteId, String listName, Map<String, Object> data, Operation operation, String rowUuid) {
        String effectiveUuid = rowUuid;
        if (operation == Operation.INSERT && (effectiveUuid == null || effectiveUuid.trim().isEmpty())) {
            effectiveUuid = (String) data.get(SQL_UUID_FIELD);
        }

        if (effectiveUuid == null || effectiveUuid.trim().isEmpty()) {
            logReplicationError(conn, listName, "(UUID nulo o vacío)", "El parámetro 'rowUuid' no puede ser nulo o vacío.");
            return;
        }

        try {
            // ** CORRECCIÓN: Ya no se obtiene el listId. Se pasa el listName directamente. **
            switch (operation) {
                case INSERT:
                    handleInsert(conn, targetSiteId, listName, data, effectiveUuid);
                    break;
                case UPDATE:
                    handleUpdate(conn, targetSiteId, listName, data, effectiveUuid);
                    break;
                case DELETE:
                    handleDelete(conn, targetSiteId, listName, effectiveUuid);
                    break;
            }
        } catch (Exception e) {
            logReplicationError(conn, listName, effectiveUuid, "Fallo genérico en la replicación: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void handleInsert(Connection conn, String targetSiteId, String listName, Map<String, Object> data, String rowUuid) throws Exception {
        if (data == null) {
            logReplicationError(conn, listName, rowUuid, "El mapa 'data' no puede ser nulo para una operación INSERT.");
            return;
        }
        
        FieldValueSet fields = new FieldValueSet();
        data.put(SQL_UUID_FIELD, rowUuid);
        fields.setAdditionalData(data);
        
        // ** CORRECCIÓN: Se pasa listName en lugar de listId **
        SharepointUtil.createListItem(targetSiteId, listName, fields);
        logSuccess(conn, "INSERT", listName, rowUuid);
    }

    private static void handleUpdate(Connection conn, String targetSiteId, String listName, Map<String, Object> data, String rowUuid) throws Exception {
        if (data == null || data.isEmpty()) {
            logReplicationWarning(conn, listName, rowUuid, "No hay datos para actualizar en operación UPDATE.");
            return;
        }
        // ** CORRECCIÓN: Se pasa listName en lugar de listId **
        ListItem itemToUpdate = findListItemByUuid(targetSiteId, listName, rowUuid);
        if (itemToUpdate == null) {
            logReplicationWarning(conn, listName, rowUuid, "UPDATE fallido: No se encontró item con el UUID. Se intentará un INSERT como alternativa.");
            handleInsert(conn, targetSiteId, listName, data, rowUuid);
            return;
        }

        FieldValueSet fields = new FieldValueSet();
        fields.setAdditionalData(data);
        // ** CORRECCIÓN: Se pasa listName en lugar de listId **
        SharepointUtil.updateListItem(targetSiteId, listName, itemToUpdate.getId(), fields);
        logSuccess(conn, "UPDATE", listName, rowUuid);
    }

    private static void handleDelete(Connection conn, String targetSiteId, String listName, String rowUuid) throws Exception {
        // ** CORRECCIÓN: Se pasa listName en lugar de listId **
        ListItem itemToDelete = findListItemByUuid(targetSiteId, listName, rowUuid);
        if (itemToDelete == null) {
            logReplicationWarning(conn, listName, rowUuid, "DELETE fallido: No se encontró item con el UUID correspondiente.");
            return;
        }
        // ** CORRECCIÓN: Se pasa listName en lugar de listId **
        SharepointUtil.deleteListItem(targetSiteId, listName, itemToDelete.getId());
        logSuccess(conn, "DELETE", listName, rowUuid);
    }

    private static ListItem findListItemByUuid(String targetSiteId, String listName, String uuid) throws Exception {
        // ** CORRECCIÓN: Se pasa listName en lugar de listId **
        ListItemCollectionResponse items = SharepointUtil.getListItems(targetSiteId, listName);
        if (items != null && items.getValue() != null) {
            for (ListItem item : items.getValue()) {
                if (item.getFields() != null && item.getFields().getAdditionalData().containsKey(SQL_UUID_FIELD)) {
                    Object itemUuidObj = item.getFields().getAdditionalData().get(SQL_UUID_FIELD);
                    if (itemUuidObj != null && uuid.equals(itemUuidObj.toString())) {
                        return item;
                    }
                }
            }
        }
        return null;
    }

    public static void deleteAllItems(Connection conn, String targetSiteId, String listName) {
        try {
            LogUtil.logOperation(conn, "REPLICATE_DELETE_ALL", "SYSTEM", "Iniciando borrado masivo en: " + listName);
            // ** CORRECCIÓN: Se pasa listName en lugar de listId y se elimina la obtención del ID **
            SharepointUtil.deleteAllListItems(targetSiteId, listName);
            LogUtil.logOperation(conn, "REPLICATE_DELETE_ALL", "SYSTEM", "Borrado masivo completado para: " + listName);
        } catch (Exception e) {
            logReplicationError(conn, listName, "N/A (Operación Masiva)", "Fallo en deleteAllItems: " + e.getMessage());
        }
    }

    // --- MÉTODOS DE LOGGING --- 

    private static void logSuccess(Connection conn, String operation, String listName, String rowUuid) {
        try {
            LogUtil.logOperation(conn, "REPLICATE_SUCCESS", "SYSTEM",
                "Operación " + operation + " replicada a SP en lista: " + listName + " para UUID: " + rowUuid);
        } catch (SQLException e) {
            System.err.println("CRITICAL: Fallo al registrar LOG de REPLICATE_SUCCESS: " + e.getMessage());
        }
    }
    
    private static void logReplicationError(Connection conn, String listName, String rowUuid, String details) {
        try {
            LogUtil.logOperation(conn, "REPLICATE_ERROR", "SYSTEM",
                "Error de replicación en lista '" + listName + "' para UUID '" + rowUuid + "': " + details);
        } catch (SQLException e) {
            System.err.println("CRITICAL: Fallo al registrar LOG de REPLICATE_ERROR: " + e.getMessage());
        }
    }

    private static void logReplicationWarning(Connection conn, String listName, String rowUuid, String details) {
        try {
            LogUtil.logOperation(conn, "REPLICATE_WARNING", "SYSTEM",
                "Warning de replicación en lista '" + listName + "' para UUID '" + rowUuid + "': " + details);
        } catch (SQLException e) {
            System.err.println("CRITICAL: Fallo al registrar LOG de REPLICATE_WARNING: " + e.getMessage());
        }
    }
}
