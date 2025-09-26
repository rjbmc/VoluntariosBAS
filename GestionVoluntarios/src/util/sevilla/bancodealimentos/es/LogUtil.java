package util.sevilla.bancodealimentos.es;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Date;

import jakarta.servlet.ServletException;

/**
 * Clase de utilidad para gestionar la escritura de registros en la tabla `logs`.
 * Centraliza toda la lógica de logging de la aplicación.
 */
public class LogUtil {

    /**
     * Registra una operación en el log usando una conexión a la BD existente.
     * @param conn La conexión activa a la base de datos.
     * @param operation El tipo de operación (ej. "ALTA", "LOGIN", "MODIF").
     * @param operator El usuario que realiza la operación.
     * @param comment Un comentario descriptivo de la operación.
     * @throws SQLException Si ocurre un error de base de datos.
     */
    public static void logOperation(Connection conn, String operation, String operator, String comment) throws SQLException {
        String sql = "INSERT INTO logs (Operación, Operador, Dia, Hora, Comentario) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, operation);
            stmt.setString(2, operator);
            
            // Usamos java.util.Date para obtener el momento actual
            Date ahora = new Date();
            stmt.setDate(3, new java.sql.Date(ahora.getTime())); // Fecha actual
            stmt.setTimestamp(4, new Timestamp(ahora.getTime())); // Fecha y hora actual
            
            stmt.setString(5, comment);
            stmt.executeUpdate();
        }
    }

    /**
     * Registra una operación en el log abriendo y cerrando su propia conexión a la BD.
     * Es útil para eventos donde no hay una transacción en curso (ej. un login fallido).
     * @param operation El tipo de operación.
     * @param operator El usuario que realiza la operación.
     * @param comment Un comentario descriptivo.
     * @throws ServletException Si no se puede registrar la operación en el log.
     */
    public static void logOperation(String operation, String operator, String comment) throws ServletException {
        try (Connection conn = DatabaseUtil.getConnection()) {
            // Reutiliza el método anterior
            logOperation(conn, operation, operator, comment);
        } catch (SQLException e) {
            System.err.println("Error CRÍTICO al registrar la operación en el log (conexión propia): " + e.getMessage());
            e.printStackTrace();
            // Envuelve el error en una ServletException para notificar al contenedor que algo ha ido mal.
            throw new ServletException("Fallo al registrar la operación en el log.", e);
        }
    }
}
