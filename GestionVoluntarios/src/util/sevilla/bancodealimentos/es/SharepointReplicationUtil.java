package util.sevilla.bancodealimentos.es;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Clase de utilidad para replicar a SharePoint una operación ya realizada en la base de datos.
 * Utiliza un identificador único universal (UUID) para vincular la fila de SQL con el item de SharePoint.
 * Esta clase captura internamente las excepciones para no interferir con el flujo principal de la aplicación.
 */
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

    public static void replicate(Connection conn, String listName, Map<String, Object> data, Operation operation, String rowUuid) {
        if (rowUuid == null || rowUuid.trim().isEmpty()) {
            logReplicationError(conn, listName, "(UUID nulo o vacío)", "El parámetro 'rowUuid' no puede ser nulo o vacío.");
            return;
        }

        try {
            switch (operation) {
                case INSERT:
                    handleInsert(conn, listName, data, rowUuid);
                    break;
                case UPDATE:
                    handleUpdate(conn, listName, data, rowUuid);
                    break;
                case DELETE:
                    handleDelete(conn, listName, rowUuid);
                    break;
            }
        } catch (Exception e) {
            // Captura cualquier excepción de las operaciones de SharePoint
            logReplicationError(conn, listName, rowUuid, "Fallo genérico en la replicación: " + e.getMessage());
        }
    }

    private static void handleInsert(Connection conn, String listName, Map<String, Object> data, String rowUuid) throws Exception {
        if (data == null) {
            logReplicationError(conn, listName, rowUuid, "El mapa 'data' no puede ser nulo para una operación INSERT.");
            return;
        }
        Map<String, Object> dataToInsert = new HashMap<>(data);
        dataToInsert.put(SQL_UUID_FIELD, rowUuid);
        SharepointUtil.createListItem(listName, dataToInsert);
        logSuccess(conn, "INSERT", listName, rowUuid);
    }

    private static void handleUpdate(Connection conn, String listName, Map<String, Object> data, String rowUuid) throws Exception {
        if (data == null || data.isEmpty()) {
            logReplicationWarning(conn, listName, rowUuid, "No hay datos para actualizar en operación UPDATE.");
            return;
        }
        String itemId = SharepointUtil.findListItemIdByFieldValue(listName, SQL_UUID_FIELD, rowUuid);
        if (itemId == null) {
            logReplicationWarning(conn, listName, rowUuid, "UPDATE fallido: No se encontró item con el UUID correspondiente.");
            return;
        }
        SharepointUtil.updateListItem(listName, itemId, data);
        logSuccess(conn, "UPDATE", listName, rowUuid);
    }

    private static void handleDelete(Connection conn, String listName, String rowUuid) throws Exception {
        String itemId = SharepointUtil.findListItemIdByFieldValue(listName, SQL_UUID_FIELD, rowUuid);
        if (itemId == null) {
            logReplicationWarning(conn, listName, rowUuid, "DELETE fallido: No se encontró item con el UUID correspondiente.");
            return;
        }
        SharepointUtil.deleteListItem(listName, itemId);
        logSuccess(conn, "DELETE", listName, rowUuid);
    }

    // --- MÉTODOS DE LOGGING INTERNOS Y SEGUROS ---

    private static void logSuccess(Connection conn, String operation, String listName, String rowUuid) {
        try {
            LogUtil.logOperation(conn, "REPLICATE_SUCCESS", "SYSTEM",
                "Operación " + operation + " replicada a SharePoint en lista: " + listName + " para UUID: " + rowUuid);
        } catch (SQLException e) {
            System.err.println("CRITICAL: Fallo al registrar un LOG de REPLICATE_SUCCESS: " + e.getMessage());
        }
    }
    
    private static void logReplicationError(Connection conn, String listName, String rowUuid, String details) {
        try {
            LogUtil.logOperation(conn, "REPLICATE_ERROR", "SYSTEM",
                "Error de replicación en lista '" + listName + "' para UUID '" + rowUuid + "': " + details);
        } catch (SQLException e) {
            System.err.println("CRITICAL: Fallo al registrar un LOG de REPLICATE_ERROR: " + e.getMessage());
        }
    }

    private static void logReplicationWarning(Connection conn, String listName, String rowUuid, String details) {
        try {
            LogUtil.logOperation(conn, "REPLICATE_WARNING", "SYSTEM",
                "Warning de replicación en lista '" + listName + "' para UUID '" + rowUuid + "': " + details);
        } catch (SQLException e) {
            System.err.println("CRITICAL: Fallo al registrar un LOG de REPLICATE_WARNING: " + e.getMessage());
        }
    }
}
