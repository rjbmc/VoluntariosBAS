package util.sevilla.bancodealimentos.es;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException; // Importar SQLException
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utilidad para la reparación y sincronización masiva de las tiendas con Microsoft SharePoint.
 * Recorre la base de datos local y asegura que cada registro exista y esté actualizado en la nube.
 */
public class RepairTiendasData {

    private static final Logger logger = LoggerFactory.getLogger(RepairTiendasData.class);

    /**
     * Ejecuta el proceso de sincronización total y reparación en SharePoint.
     * @throws SQLException Si ocurre un error de base de datos al preparar la consulta.
     * @throws Exception Si ocurre otro error crítico durante la configuración.
     */
    public static void repairSharePointUUIDs() throws SQLException, Exception {
        logger.info("Iniciando proceso de sincronización total y reparación en SharePoint...");

        String sql = "SELECT * FROM tiendas";
        
        int procesados = 0;
        int errores = 0;

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                try {
                    Map<String, Object> storeData = new HashMap<>();
                    storeData.put("codigo", rs.getInt("codigo"));
                    storeData.put("SqlRowUUID", rs.getString("SqlRowUUID"));
                    storeData.put("denominacion", rs.getString("denominacion"));
                    storeData.put("direccion", rs.getString("Direccion"));
                    storeData.put("lat", rs.getString("Lat"));
                    storeData.put("lon", rs.getString("Lon"));
                    storeData.put("cp", rs.getString("cp"));
                    storeData.put("poblacion", rs.getString("Poblacion"));
                    storeData.put("cadena", rs.getString("Cadena"));
                    storeData.put("disponible", rs.getString("disponible"));
                    storeData.put("prioridad", rs.getString("prioridad"));
                    storeData.put("huecosTurno1", rs.getInt("HuecosTurno1"));
                    storeData.put("huecosTurno2", rs.getInt("HuecosTurno2"));
                    storeData.put("huecosTurno3", rs.getInt("HuecosTurno3"));
                    storeData.put("huecosTurno4", rs.getInt("HuecosTurno4"));
                    storeData.put("supervisor", rs.getString("Supervisor"));
                    storeData.put("coordinador", rs.getString("Coordinador"));
                    storeData.put("marca", rs.getString("Marca"));
                    storeData.put("codZona", rs.getString("CodZona"));
                    storeData.put("zona", rs.getString("Zona"));
                    storeData.put("modalidad", rs.getString("Modalidad"));

                    SharePointUtil.syncTienda(conn, storeData);
                    
                    procesados++;
                    if (procesados % 10 == 0) {
                        logger.info("Progreso: {} tiendas procesadas satisfactoriamente...", procesados);
                    }

                } catch (Exception e) {
                    // Este catch se mantiene para que un error en una tienda no detenga todo el proceso
                    logger.error("Error al procesar la tienda con código {}: {}. Se continúa con la siguiente.", rs.getString("codigo"), e.getMessage());
                    errores++;
                }
            }

            logger.info("Proceso de sincronización finalizado. Resumen -> Sincronizadas: {}. Errores: {}.", procesados, errores);

        } 
        // El try-with-resources se encarga de cerrar la conexión.
        // Cualquier SQLException en el bloque try-with-resources (getConnection, prepareStatement, etc.) 
        // ahora se propagará hacia arriba, al servlet, que es lo que queremos.
    }
}
