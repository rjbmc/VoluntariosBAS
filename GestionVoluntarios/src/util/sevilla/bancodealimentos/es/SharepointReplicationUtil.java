package util.sevilla.bancodealimentos.es;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.microsoft.graph.models.FieldValueSet;

// UTILIDAD PARA REPLICAR CAMBIOS DE UN ÚNICO ELEMENTO (CRUD)
public final class SharepointReplicationUtil {

    // 1. Logger SLF4J
    private static final Logger logger = LoggerFactory.getLogger(SharepointReplicationUtil.class);

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
        String listId = null;
        try {
            listId = SharepointUtil.getListId(targetSiteId, listName);
            if (listId == null) {
                throw new Exception("La lista '" + listName + "' no se encontró en el sitio de SharePoint.");
            }
        } catch (Exception e) {
            logger.error("Error al obtener ID de lista SharePoint '{}'", listName, e);
            logReplicationError(conn, listName, rowUuid, "No se pudo obtener el ID de la lista: " + e.getMessage());
            return;
        }

        try {
            logger.debug("Iniciando replicación: {} en lista {}", operation, listName);
            switch (operation) {
                case INSERT:
                    handleInsert(conn, targetSiteId, listId, listName, data, rowUuid);
                    break;
                case UPDATE:
                    handleUpdate(conn, targetSiteId, listId, listName, data, rowUuid);
                    break;
                case DELETE:
                    handleDelete(conn, targetSiteId, listId, listName, rowUuid);
                    break;
            }
        } catch (Exception e) {
            logger.error("Fallo genérico en la replicación para UUID {}", rowUuid, e);
            logReplicationError(conn, listName, rowUuid, "Fallo genérico en la replicación: " + e.getMessage());
        }
    }

    private static void handleInsert(Connection conn, String targetSiteId, String listId, String listName, Map<String, Object> data, String rowUuid) throws Exception {
        if (data == null) {
            logReplicationError(conn, listName, rowUuid, "El mapa 'data' no puede ser nulo para una operación INSERT.");
            return;
        }
        
        FieldValueSet fields = new FieldValueSet();
        data.put(SQL_UUID_FIELD, rowUuid);
        fields.setAdditionalData(data);
        
        SharepointUtil.createListItem(targetSiteId, listId, fields);
        logSuccess(conn, "INSERT", listName, rowUuid);
    }

    private static void handleUpdate(Connection conn, String targetSiteId, String listId, String listName, Map<String, Object> data, String rowUuid) throws Exception {
        if (data == null || data.isEmpty()) {
            logReplicationWarning(conn, listName, rowUuid, "No hay datos para actualizar en operación UPDATE.");
            return;
        }

        String itemId = SharepointUtil.findItemIdByFieldValue(targetSiteId, listId, SQL_UUID_FIELD, rowUuid);

        if (itemId == null) {
            logReplicationWarning(conn, listName, rowUuid, "UPDATE fallido: No se encontró item con el UUID. Se intentará un INSERT como alternativa.");
            handleInsert(conn, targetSiteId, listId, listName, data, rowUuid); // Intenta crear el registro si no existe
            return;
        }

        FieldValueSet fields = new FieldValueSet();
        fields.setAdditionalData(data);
        SharepointUtil.updateListItem(targetSiteId, listId, itemId, fields);
        logSuccess(conn, "UPDATE", listName, rowUuid);
    }

    private static void handleDelete(Connection conn, String targetSiteId, String listId, String listName, String rowUuid) throws Exception {
        String itemId = SharepointUtil.findItemIdByFieldValue(targetSiteId, listId, SQL_UUID_FIELD, rowUuid);

        if (itemId == null) {
            logReplicationWarning(conn, listName, rowUuid, "DELETE fallido: No se encontró item con el UUID correspondiente.");
            return;
        }
        SharepointUtil.deleteListItem(targetSiteId, listId, itemId);
        logSuccess(conn, "DELETE", listName, rowUuid);
    }

    // --- MÉTODOS DE LOGGING --- 

    private static void logSuccess(Connection conn, String operation, String listName, String rowUuid) {
        try {
            LogUtil.logOperation(conn, "REPLICATE_SUCCESS", "SYSTEM",
                "Operación " + operation + " replicada a SP en lista: " + listName + " para UUID: " + rowUuid);
        } catch (SQLException e) {
            // Si falla el log en base de datos, usamos el logger de archivo como respaldo crítico
            logger.error("CRITICAL: Fallo al registrar LOG de REPLICATE_SUCCESS en base de datos", e);
        }
    }
    
    private static void logReplicationError(Connection conn, String listName, String rowUuid, String details) {
        try {
            LogUtil.logOperation(conn, "REPLICATE_ERROR", "SYSTEM",
                "Error de replicación en lista '" + listName + "' para UUID '" + rowUuid + "': " + details);
        } catch (SQLException e) {
            logger.error("CRITICAL: Fallo al registrar LOG de REPLICATE_ERROR en base de datos", e);
        }
    }

    private static void logReplicationWarning(Connection conn, String listName, String rowUuid, String details) {
        try {
            LogUtil.logOperation(conn, "REPLICATE_WARNING", "SYSTEM",
                "Warning de replicación en lista '" + listName + "' para UUID '" + rowUuid + "': " + details);
        } catch (SQLException e) {
            logger.error("CRITICAL: Fallo al registrar LOG de REPLICATE_WARNING en base de datos", e);
        }
    }
}