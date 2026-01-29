package util.sevilla.bancodealimentos.es;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class TiendasUtil {

    private static final Logger LOGGER = Logger.getLogger(TiendasUtil.class.getName());

    /**
     * Reconstruye las tiendas en el sistema.
     * Este método contiene la lógica para realizar la reconstrucción de las tiendas.
     * @return true si la reconstrucción fue exitosa, false en caso contrario.
     * @throws SQLException Si ocurre un error de base de datos.
     */
    public static boolean rebuildTiendas() throws SQLException {
        try (Connection conn = DatabaseUtil.getConnection()) {
            LOGGER.log(Level.INFO, "Iniciando la reconstrucción de tiendas...");

            // Eliminar datos existentes en la tabla de tiendas
            String deleteSQL = "DELETE FROM tiendas";
            try (PreparedStatement deleteStmt = conn.prepareStatement(deleteSQL)) {
                deleteStmt.executeUpdate();
                LOGGER.log(Level.INFO, "Datos existentes eliminados de la tabla de tiendas.");
            }

            // Insertar nuevos datos en la tabla de tiendas
            String insertSQL = "INSERT INTO tiendas (nombre, direccion, telefono) VALUES (?, ?, ?)";
            try (PreparedStatement insertStmt = conn.prepareStatement(insertSQL)) {
                insertStmt.setString(1, "Tienda 1");
                insertStmt.setString(2, "Calle Principal 123");
                insertStmt.setString(3, "123456789");
                insertStmt.executeUpdate();

                insertStmt.setString(1, "Tienda 2");
                insertStmt.setString(2, "Avenida Secundaria 456");
                insertStmt.setString(3, "987654321");
                insertStmt.executeUpdate();

                LOGGER.log(Level.INFO, "Nuevos datos insertados en la tabla de tiendas.");
            }

            LOGGER.log(Level.INFO, "Reconstrucción de tiendas completada exitosamente.");
            return true;
        }
    }
}
