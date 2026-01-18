package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger; // ¡CORREGIDO!
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.graph.models.FieldValueSet;
import com.microsoft.graph.models.ListItem;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import util.sevilla.bancodealimentos.es.DatabaseUtil;
import util.sevilla.bancodealimentos.es.LogUtil;
import util.sevilla.bancodealimentos.es.SharePointUtil;

@WebServlet("/sync-tiendas")
public class SyncTiendasServlet extends HttpServlet {
    private static final long serialVersionUID = 2L; // Versión actualizada a bidireccional
    
    private static final Logger logger = LoggerFactory.getLogger(SyncTiendasServlet.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    private static final String SHAREPOINT_LIST_NAME = "Tiendas";

    // --- CLASES INTERNAS PARA DATOS ---

    // Clase para almacenar datos de la tienda en memoria
    private static class TiendaData {
        int codigo; String denominacion; String sqlRowUUID;
        String direccion; BigDecimal lat; BigDecimal lon; String cp; String poblacion; String cadena; 
        int prioridad; boolean disponible; int huecos1, huecos2, huecos3, huecos4;
        String supervisor; // Nuevo campo
        String coordinador; // Nuevo campo

        // Constructor desde la Base de Datos
        TiendaData(ResultSet rs) throws SQLException {
            this.codigo = rs.getInt("codigo");
            this.denominacion = rs.getString("denominacion");
            this.sqlRowUUID = rs.getString("SqlRowUUID");
            this.direccion = rs.getString("Direccion");
            this.lat = rs.getBigDecimal("Lat");
            this.lon = rs.getBigDecimal("Lon");
            this.cp = rs.getString("cp");
            this.poblacion = rs.getString("Poblacion");
            this.cadena = rs.getString("Cadena");
            // Leer prioridad como String (o int y luego formatear si se necesita el int en otros sitios)
            // Aquí asumo que la BD ya puede tenerlo como VARCHAR o que lo lees como int y lo usas como int internamente
            // pero para el propósito de guardar en SP o de nuevo en DB, se formateará a String
            this.prioridad = Integer.parseInt(rs.getString("prioridad")); // Asumo que se leerá como String ahora
            this.disponible = "S".equalsIgnoreCase(rs.getString("disponible"));
            this.huecos1 = rs.getInt("HuecosTurno1");
            this.huecos2 = rs.getInt("HuecosTurno2");
            this.huecos3 = rs.getInt("HuecosTurno3");
            this.huecos4 = rs.getInt("HuecosTurno4");
            this.supervisor = rs.getString("Supervisor"); // Leer Supervisor de DB
            this.coordinador = rs.getString("Coordinador"); // Leer Coordinador de DB
        }

        // NUEVO: Constructor desde un item de SharePoint
        TiendaData(Map<String, Object> spFields) {
            this.sqlRowUUID = (String) spFields.get("SqlRowUUID");
            this.denominacion = (String) spFields.get("Title");
            
            Object codigoObj = spFields.get("codigo");
            if (codigoObj instanceof Double) { this.codigo = ((Double) codigoObj).intValue(); }
            else if (codigoObj instanceof String) { this.codigo = Integer.parseInt((String) codigoObj); }
            else { this.codigo = 0; }

            this.direccion = (String) spFields.get("direccion");
            this.lat = spFields.get("lat") != null ? new BigDecimal(String.valueOf(spFields.get("lat"))) : null;
            this.lon = spFields.get("lon") != null ? new BigDecimal(String.valueOf(spFields.get("lon"))) : null;
            this.cp = (String) spFields.get("cp");
            this.poblacion = (String) spFields.get("poblacion");
            this.cadena = (String) spFields.get("cadena");

            // Si SharePoint envía "0001" como String, se parseará a int.
            // Si SharePoint envía un número, se tratará como tal.
            Object prioridadObj = spFields.get("prioridad");
            if (prioridadObj instanceof Double) { this.prioridad = ((Double) prioridadObj).intValue(); }
            else if (prioridadObj instanceof String) { this.prioridad = Integer.parseInt((String) prioridadObj); }
            else { this.prioridad = 0; }

            this.disponible = spFields.get("disponible") instanceof Boolean ? (Boolean) spFields.get("disponible") : false;
            
            this.huecos1 = spFields.get("huecosTurno1") instanceof Double ? ((Double) spFields.get("huecosTurno1")).intValue() : 0;
            this.huecos2 = spFields.get("huecosTurno2") instanceof Double ? ((Double) spFields.get("huecosTurno2")).intValue() : 0;
            this.huecos3 = spFields.get("huecosTurno3") instanceof Double ? ((Double) spFields.get("huecosTurno3")).intValue() : 0;
            this.huecos4 = spFields.get("huecosTurno4") instanceof Double ? ((Double) spFields.get("huecosTurno4")).intValue() : 0;

            this.supervisor = (String) spFields.get("Supervisor"); // Mapear Supervisor de SP a TiendaData
            this.coordinador = (String) spFields.get("Coordinador"); // Mapear Coordinador de SP a TiendaData
        }
    }
    
    // Clase para almacenar el resultado de la sincronización
    private static class SyncResult {
        int spToDbCreated = 0;
        int dbToSpCreated = 0;
        
        @Override
        public String toString() {
            return String.format("SP->BD: %d nuevas. BD->SP: %d nuevas.", spToDbCreated, dbToSpCreated);
        }
    }

    // --- MÉTODO PRINCIPAL ---

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        Map<String, Object> jsonResponse = new HashMap<>();
        HttpSession session = request.getSession(false);

        if (session == null || session.getAttribute("usuario") == null || !"S".equals(session.getAttribute("isAdmin"))) {
            logger.warn("Acceso denegado a SyncTiendas. IP: {}", request.getRemoteAddr());
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            jsonResponse.put("success", false);
            jsonResponse.put("message", "Acceso denegado. Permisos insuficientes.");
            objectMapper.writeValue(response.getWriter(), jsonResponse);
            return;
        }

        String adminUser = (String) session.getAttribute("usuario");
        logger.info("Iniciando sincronización BIDIRECCIONAL de tiendas. Solicitado por: {}", adminUser);

        try (Connection conn = DatabaseUtil.getConnection()) {
            conn.setAutoCommit(false); // Transacción para seguridad
            
            String listId = SharePointUtil.getListId(SharePointUtil.SITE_ID, SHAREPOINT_LIST_NAME);
            if (listId == null) throw new Exception("La lista '" + SHAREPOINT_LIST_NAME + "' no fue encontrada en SharePoint.");

            // 1. LEER AMBAS FUENTES
            logger.debug("Fase 1: Leyendo tiendas de la BD y de SharePoint...");
            Map<String, TiendaData> dbTiendas = getDbTiendasAsMap(conn);
            Map<String, ListItem> spTiendas = getSharePointItemsAsMap(listId);
            logger.info("Lectura completa: {} tiendas en BD, {} en SharePoint.", dbTiendas.size(), spTiendas.size());
            
            SyncResult result = new SyncResult();

            // 2. SINCRONIZAR SP -> BD
            logger.debug("Fase 2: Sincronizando nuevas tiendas de SharePoint a la Base de Datos...");
            syncSharePointToDb(conn, listId, spTiendas, dbTiendas, result);
            
            // 3. SINCRONIZAR BD -> SP
            // Recargamos los mapas por si ha habido cambios en la fase anterior
            Map<String, TiendaData> dbTiendasActualizadas = getDbTiendasAsMap(conn);
            Map<String, ListItem> spTiendasActualizadas = getSharePointItemsAsMap(listId);
            logger.debug("Fase 3: Sincronizando nuevas tiendas de la Base de Datos a SharePoint...");
            syncDbToSharePoint(conn, listId, dbTiendasActualizadas, spTiendasActualizadas, result);
            
            conn.commit(); // Confirmar todos los cambios en la BD
            
            String successMessage = "Sincronización bidireccional completada. " + result.toString();
            logger.info(successMessage);
            LogUtil.logOperation(conn, "SYNC_TIENDAS", adminUser, successMessage);
            
            jsonResponse.put("success", true);
            jsonResponse.put("message", successMessage);

        } catch (Exception e) {
            logger.error("Error crítico durante la sincronización de tiendas. Se hará rollback si es posible.", e);
            // No podemos hacer rollback en una conexión cerrada, pero el try-with-resources lo intentará
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            jsonResponse.put("success", false);
            jsonResponse.put("message", "Error durante el ciclo de sincronización: " + e.getMessage());
        } finally {
            objectMapper.writeValue(response.getWriter(), jsonResponse);
        }
    }
    
    // --- MÉTODOS DE SINCRONIZACIÓN ---

    private void syncSharePointToDb(Connection conn, String listId, Map<String, ListItem> spTiendas, Map<String, TiendaData> dbTiendas, SyncResult result) throws Exception {
        for (ListItem spItem : spTiendas.values()) {
            Map<String, Object> spFields = spItem.getFields().getAdditionalData();
            String spUuid = (String) spFields.get("SqlRowUUID");

            if (spUuid == null || spUuid.isEmpty() || !dbTiendas.containsKey(spUuid)) {
                logger.info("Detectada nueva tienda en SharePoint (Título: '{}'). Creando en BD local...", spFields.get("Title"));
                
                String nuevoUuid = UUID.randomUUID().toString();
                TiendaData nuevaTienda = new TiendaData(spFields);
                
                insertTiendaInDb(conn, nuevaTienda, nuevoUuid);
                
                FieldValueSet fieldToUpdate = new FieldValueSet();
                fieldToUpdate.getAdditionalData().put("SqlRowUUID", nuevoUuid);
                SharePointUtil.updateListItem(SharePointUtil.SITE_ID, listId, spItem.getId(), fieldToUpdate);
                
                logger.info("Tienda '{}' creada en BD con UUID {} y actualizada en SharePoint.", nuevaTienda.denominacion, nuevoUuid);
                result.spToDbCreated++;
            }
        }
    }

    private void syncDbToSharePoint(Connection conn, String listId, Map<String, TiendaData> dbTiendas, Map<String, ListItem> spTiendas, SyncResult result) throws Exception {
        for (TiendaData dbTienda : dbTiendas.values()) {
            if (dbTienda.sqlRowUUID == null || dbTienda.sqlRowUUID.isEmpty() || !spTiendas.containsKey(dbTienda.sqlRowUUID)) {
                logger.info("Detectada nueva tienda en BD (Código: {}). Creando en SharePoint...", dbTienda.codigo);

                String uuidParaSp = (dbTienda.sqlRowUUID == null || dbTienda.sqlRowUUID.isEmpty()) ? UUID.randomUUID().toString() : dbTienda.sqlRowUUID;
                
                // Si la tienda no tenía UUID en la BD, se lo ponemos ahora.
                if (dbTienda.sqlRowUUID == null || dbTienda.sqlRowUUID.isEmpty()) {
                    updateDbTiendaUuid(conn, dbTienda.codigo, uuidParaSp);
                }

                FieldValueSet newFields = createFieldsFromTiendaData(dbTienda, uuidParaSp);
                SharePointUtil.createListItem(SharePointUtil.SITE_ID, listId, newFields);
                logger.info("Tienda '{}' creada en SharePoint con UUID {}.", dbTienda.denominacion, uuidParaSp);
                result.dbToSpCreated++;
            }
        }
    }

    // --- MÉTODOS DE AYUDA (HELPERS) ---
    
    private Map<String, TiendaData> getDbTiendasAsMap(Connection conn) throws SQLException {
        Map<String, TiendaData> map = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM tiendas"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                TiendaData tienda = new TiendaData(rs);
                if (tienda.sqlRowUUID != null && !tienda.sqlRowUUID.isEmpty()) {
                    map.put(tienda.sqlRowUUID, tienda);
                }
            }
        }
        return map;
    }

    private Map<String, ListItem> getSharePointItemsAsMap(String listId) throws Exception {
        Map<String, ListItem> map = new HashMap<>();
        for (ListItem item : SharePointUtil.getAllListItems(SharePointUtil.SITE_ID, listId)) {
            Map<String, Object> fields = item.getFields().getAdditionalData();
            String uuid = (String) fields.get("SqlRowUUID");
            if (uuid != null && !uuid.isEmpty()) {
                map.put(uuid, item);
            } else {
                // Se usa el ID de SP como clave temporal para tiendas sin UUID
                map.put("SP_ID_" + item.getId(), item); 
            }
        }
        return map;
    }
    
    private void insertTiendaInDb(Connection conn, TiendaData tienda, String nuevoUuid) throws SQLException {
        String sql = "INSERT INTO tiendas (codigo, denominacion, SqlRowUUID, Direccion, Lat, Lon, cp, Poblacion, Cadena, prioridad, disponible, HuecosTurno1, HuecosTurno2, HuecosTurno3, HuecosTurno4, Supervisor, Coordinador) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            // El código de tienda debe ser único, si viene a 0 de SP, se debe gestionar.
            // Por ahora, se asume que se importa un código válido.
            ps.setInt(1, tienda.codigo); 
            ps.setString(2, tienda.denominacion);
            ps.setString(3, nuevoUuid);
            ps.setString(4, tienda.direccion);
            ps.setBigDecimal(5, tienda.lat);
            ps.setBigDecimal(6, tienda.lon);
            ps.setString(7, tienda.cp);
            ps.setString(8, tienda.poblacion);
            ps.setString(9, tienda.cadena);
            ps.setString(10, String.format("%04d", tienda.prioridad)); // ¡CORREGIDO! Formatear prioridad a 4 dígitos con ceros iniciales
            ps.setString(11, tienda.disponible ? "S" : "N");
            ps.setInt(12, tienda.huecos1);
            ps.setInt(13, tienda.huecos2);
            ps.setInt(14, tienda.huecos3);
            ps.setInt(15, tienda.huecos4);
            ps.setString(16, tienda.supervisor); // Insertar Supervisor
            ps.setString(17, tienda.coordinador); // Insertar Coordinador
            ps.executeUpdate();
        }
    }
    
    private void updateDbTiendaUuid(Connection conn, int codigoTienda, String uuid) throws SQLException {
        String sql = "UPDATE tiendas SET SqlRowUUID = ? WHERE codigo = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, uuid);
            ps.setInt(2, codigoTienda);
            ps.executeUpdate();
        }
    }
    
    private FieldValueSet createFieldsFromTiendaData(TiendaData tienda, String uuid) {
        FieldValueSet fields = new FieldValueSet();
        fields.getAdditionalData().put("Title", tienda.denominacion);
        fields.getAdditionalData().put("codigo", String.valueOf(tienda.codigo));
        fields.getAdditionalData().put("denominacion", tienda.denominacion);
        fields.getAdditionalData().put("direccion", tienda.direccion);
        fields.getAdditionalData().put("lat", tienda.lat);
        fields.getAdditionalData().put("lon", tienda.lon);
        fields.getAdditionalData().put("cp", tienda.cp);
        fields.getAdditionalData().put("poblacion", tienda.poblacion);
        fields.getAdditionalData().put("cadena", tienda.cadena);
        fields.getAdditionalData().put("prioridad", String.format("%04d", tienda.prioridad)); // Formatear prioridad a 4 dígitos con ceros iniciales para SharePoint
        fields.getAdditionalData().put("disponible", tienda.disponible);
        fields.getAdditionalData().put("huecosTurno1", tienda.huecos1);
        fields.getAdditionalData().put("huecosTurno2", tienda.huecos2);
        fields.getAdditionalData().put("huecosTurno3", tienda.huecos3);
        fields.getAdditionalData().put("huecosTurno4", tienda.huecos4);
        fields.getAdditionalData().put("Supervisor", tienda.supervisor); // Añadir Supervisor a SP
        fields.getAdditionalData().put("Coordinador", tienda.coordinador); // Añadir Coordinador a SP
        fields.getAdditionalData().put("SqlRowUUID", uuid);
        return fields;
    }
}
