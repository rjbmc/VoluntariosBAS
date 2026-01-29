package util.sevilla.bancodealimentos.es;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Date;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Clase de utilidad para gestionar la escritura de registros en la tabla `logs`.
 * Centraliza toda la lógica de logging de la aplicación.
 */
public class LogUtil {

    private static final Logger logger = LoggerFactory.getLogger(LogUtil.class);

    /**
     * Registra una operación en el log usando una conexión a la BD existente.
     * @param conn La conexión activa a la base de datos.
     * @param operation El tipo de operación (ej. "ALTA", "LOGIN", "MODIF").
     * @param operator El usuario que realiza la operación.
     * @param comment Un comentario descriptivo de la operación.
     * @throws SQLException Si ocurre un error de base de datos.
     */
    public static void logOperation(Connection conn, String operation, String operator, String comment) throws SQLException {
        // SQL adaptado a la estructura de la tabla existente (con tilde en Operación)
        String sql = "INSERT INTO logs (Operación, Operador, Dia, Hora, Comentario) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, operation);
            stmt.setString(2, operator);
            
            Date ahora = new Date();
            stmt.setDate(3, new java.sql.Date(ahora.getTime())); // Columna Dia
            stmt.setTimestamp(4, new Timestamp(ahora.getTime())); // Columna Hora
            
            stmt.setString(5, comment);
            stmt.executeUpdate();
        }
    }

    /**
     * Registra una operación en el log abriendo y cerrando su propia conexión a la BD.
     * @param operation El tipo de operación.
     * @param operator El usuario que realiza la operación.
     * @param comment Un comentario descriptivo.
     */
    public static void logOperation(String operation, String operator, String comment) {
        try (Connection conn = DatabaseUtil.getConnection()) {
            logOperation(conn, operation, operator, comment);
        } catch (SQLException e) {
            logger.error("Error CRÍTICO al registrar operación de auditoría en BD (op: {}, usuario: {}). Causa: ", operation, operator, e);
        }
    }

    /**
     * Registra una excepción en el log estándar y en la base de datos.
     * @param classLogger El logger de la clase donde ocurrió el error.
     * @param ex La excepción que se capturó.
     * @param message Un mensaje descriptivo del contexto del error.
     * @param username (Opcional) El nombre del usuario asociado al error.
     */
    public static void logException(Logger classLogger, Throwable ex, String message, String username) {
        StringWriter sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));
        String stackTrace = sw.toString();

        String dbComment = message + "\n--- STACK TRACE ---\n" + stackTrace;
        
        classLogger.error(message + " | Usuario: " + (username != null ? username : "N/A"), ex);

        try {
            logOperation("ERROR", (username != null ? username : "Sistema"), dbComment);
        } catch (Exception loggingEx) {
            logger.error("FALLO CRÍTICO DEL SISTEMA DE LOGS. No se pudo registrar el error en la BD.", loggingEx);
        }
    }
    
    /**
     * Sobrecarga para cuando no hay un usuario específico.
     */
    public static void logException(Logger classLogger, Throwable ex, String message) {
        logException(classLogger, ex, message, null);
    }
}
