package util.sevilla.bancodealimentos.es;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Date;

import jakarta.servlet.ServletException;

/**
 * Clase de utilidad para gestionar la escritura de registros en la tabla `logs`.
 * Centraliza toda la lÃ³gica de logging de la aplicaciÃ³n.
 */
public class LogUtil {

    /**
     * Registra una operaciÃ³n en el log usando una conexiÃ³n a la BD existente.
     * @param conn La conexiÃ³n activa a la base de datos.
     * @param operation El tipo de operaciÃ³n (ej. "ALTA", "LOGIN", "MODIF").
     * @param operator El usuario que realiza la operaciÃ³n.
     * @param comment Un comentario descriptivo de la operaciÃ³n.
     * @throws SQLException Si ocurre un error de base de datos.
     */
    public static void logOperation(Connection conn, String operation, String operator, String comment) throws SQLException {
        String sql = "INSERT INTO logs (OperaciÃ³n, Operador, Dia, Hora, Comentario) VALUES (?, ?, ?, ?, ?)";
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
     * Registra una operaciÃ³n en el log abriendo y cerrando su propia conexiÃ³n a la BD.
     * Es Ãºtil para eventos donde no hay una transacciÃ³n en curso (ej. un login fallido).
     * @param operation El tipo de operaciÃ³n.
     * @param operator El usuario que realiza la operaciÃ³n.
     * @param comment Un comentario descriptivo.
     * @throws ServletException Si no se puede registrar la operaciÃ³n en el log.
     */
    public static void logOperation(String operation, String operator, String comment) throws ServletException {
        try (Connection conn = DatabaseUtil.getConnection()) {
            // Reutiliza el mÃ©todo anterior
            logOperation(conn, operation, operator, comment);
        } catch (SQLException e) {
            System.err.println("Error CRÃTICO al registrar la operaciÃ³n en el log (conexiÃ³n propia): " + e.getMessage());
            e.printStackTrace();
            // Envuelve el error en una ServletException para notificar al contenedor que algo ha ido mal.
            throw new ServletException("Fallo al registrar la operaciÃ³n en el log.", e);
        }
    }
}

