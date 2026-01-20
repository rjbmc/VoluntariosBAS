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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.graph.models.FieldValueSet;
import com.microsoft.graph.models.ListItem;
import com.microsoft.graph.models.ListItemCollectionResponse; // Import necesario para getListItems

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
    private static final long serialVersionUID = 2L; 
    
    private static final Logger logger = LoggerFactory.getLogger(SyncTiendasServlet.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    private static final String SHAREPOINT_LIST_NAME = "Tiendas";

    // --- CLASES INTERNAS PARA DATOS ---

    // Clase para almacenar datos de la tienda en memoria
    private static class TiendaData {
        int codigo; String denominacion; String sqlRowUUID;
        String direccion; BigDecimal lat; BigDecimal lon; String cp; String poblacion; String cadena; 
        int prioridad; boolean disponible; int huecos1, huecos2, huecos3, huecos4;
        String supervisor; String coordinador;
        // NUEVOS CAMPOS REQUERIDOS POR LA DB
        String marca; String codZona; String zona; String modalidad;

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
            this.prioridad = Integer.parseInt(rs.getString("prioridad")); 
            this.disponible = "S".equalsIgnoreCase(rs.getString("disponible"));
            this.huecos1 = rs.getInt("HuecosTurno1");
            this.huecos2 = rs.getInt("HuecosTurno2");
            this.huecos3 = rs.getInt("HuecosTurno3");
            this.huecos4 = rs.getInt("HuecosTurno4");
            this.supervisor = rs.getString("Supervisor"); 
            this.coordinador = rs.getString("Coordinador"); 
            this.marca = rs.getString("Marca");
            this.codZona = rs.getString("CodZona");
            this.zona = rs.getString("Zona");
            this.modalidad = rs.getString("Modalidad");
        }

        TiendaData(Map<String, Object> spFields) {
            // DEBUGGING: Log all fields from SharePoint item (Opcional, lo mantengo por si acaso)
            if (logger.isDebugEnabled()) {
                logger.debug("Campos de SharePoint para item (Título: {}):", spFields.get("Title"));
                for (Map.Entry<String, Object> entry : spFields.entrySet()) {
                    logger.debug("  Key: {}, Value: {} (Type: {})", entry.getKey(), entry.getValue(), entry.getValue() != null ? entry.getValue().getClass().getName() : "null");
                }
            }

            this.sqlRowUUID = (String) spFields.get("SqlRowUUID");
            this.denominacion = (String) spFields.get("Title");
            
            Object codigoObj = spFields.get("codigo");
            if (codigoObj instanceof Double) { this.codigo = ((Double) codigoObj).intValue(); }
            else if (codigoObj instanceof String) { this.codigo = Integer.parseInt((String) codigoObj); }
            else { this.codigo = 0; }

            this.direccion = (String) spFields.get("direccion");
            
            Object latObj = spFields.get("lat");
            if (latObj != null) {
                String latStr = String.valueOf(latObj).replace(',', '.'); 
                this.lat = new BigDecimal(latStr);
            } else { this.lat = BigDecimal.ZERO; }

            Object lonObj = spFields.get("lon");
            if (lonObj != null) {
                String lonStr = String.valueOf(lonObj).replace(',', '.'); 
                this.lon = new BigDecimal(lonStr);
            } else { this.lon = BigDecimal.ZERO; }

            this.cp = (String) spFields.get("cp");
            this.poblacion = (String) spFields.get("poblacion");
            this.cadena = (String) spFields.get("cadena");

            Object prioridadObj = spFields.get("prioridad");
            if (prioridadObj instanceof Double) { this.prioridad = ((Double) prioridadObj).intValue(); }
            else if (prioridadObj instanceof String) { this.prioridad = Integer.parseInt((String) prioridadObj); }
            else { this.prioridad = 0; }

            this.disponible = spFields.get("disponible") instanceof Boolean ? (Boolean) spFields.get("disponible") : false;
            
            this.huecos1 = spFields.get("huecosTurno1") instanceof Double ? ((Double) spFields.get("huecosTurno1")).intValue() : 0;
            this.huecos2 = spFields.get("huecosTurno2") instanceof Double ? ((Double) spFields.get("huecosTurno2")).intValue() : 0;
            this.huecos3 = spFields.get("huecosTurno3") instanceof Double ? ((Double) spFields.get("huecosTurno3")).intValue() : 0;
            this.huecos4 = spFields.get("huecosTurno4") instanceof Double ? ((Double) spFields.get("huecosTurno4")).intValue() : 0;

            this.supervisor = spFields.get("SUPERVISOR") != null ? (String) spFields.get("SUPERVISOR") : ""; 
            this.coordinador = spFields.get("Coordinador") != null ? (String) spFields.get("Coordinador") : ""; 
            
            // Mapeo de nuevos campos con fallback a cadena vacía para evitar errores NOT NULL
            this.marca = spFields.get("Marca") != null ? (String) spFields.get("Marca") : "";
            this.codZona = spFields.get("CodZona") != null ? (String) spFields.get("CodZona") : "";
            this.zona = spFields.get("Zona") != null ? (String) spFields.get("Zona") : "";
            this.modalidad = spFields.get("Modalidad") != null ? (String) spFields.get("Modalidad") : "";
        }
    }
    
    private static class SyncResult {
        int spToDbCreated = 0;
        int spToDbUpdated = 0; 
        int dbToSpCreated = 0;
        @Override
        public String toString() {
            return String.format("SP->BD: %d nuevas, %d actualizadas. BD->SP: %d nuevas.", spToDbCreated, spToDbUpdated, dbToSpCreated);
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        Map<String, Object> jsonResponse = new HashMap<>();
        HttpSession session = request.getSession(false);

        boolean isAdmin = session != null && 
                          session.getAttribute("usuario") != null && 
                          (Boolean) session.getAttribute("isAdmin"); 

        if (!isAdmin) {
            logger.warn("Acceso denegado a SyncTiendas. Usuario: {}, IP: {}", 
                        (session != null ? session.getAttribute("usuario") : "Anónimo"), request.getRemoteAddr());
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            jsonResponse.put("success", false);
            jsonResponse.put("message", "Acceso denegado.");
            objectMapper.writeValue(response.getWriter(), jsonResponse);
            return;
        }

        String adminUser = (String) session.getAttribute("usuario");
        logger.info("Iniciando sincronización BIDIRECCIONAL de tiendas. Solicitado por: {}", adminUser);

        try (Connection conn = DatabaseUtil.getConnection()) {
            conn.setAutoCommit(false); 
            
            String listId = SharePointUtil.getListId(SharePointUtil.SITE_ID, SHAREPOINT_LIST_NAME);
            if (listId == null) {
                 logger.error("Error en sincronización: La lista '{}' no fue encontrada en SharePoint.", SHAREPOINT_LIST_NAME);
                 throw new Exception("La lista '" + SHAREPOINT_LIST_NAME + "' no fue encontrada en SharePoint.");
            }

            logger.debug("Fase 1: Leyendo tiendas de la BD y de SharePoint...");
            Map<String, TiendaData> dbTiendas = getDbTiendasAsMap(conn);
            Map<String, ListItem> spTiendas = getSharePointItemsAsMap(listId); // CORREGIDO: Usar getListItems internamente
            logger.info("Lectura completa: {} tiendas en BD, {} en SharePoint.", dbTiendas.size(), spTiendas.size());
            
            SyncResult result = new SyncResult();

            logger.debug("Fase 2: Sincronizando tiendas de SharePoint a la Base de Datos...");
            syncSharePointToDb(conn, listId, spTiendas, dbTiendas, result);
            
            Map<String, TiendaData> dbTiendasActualizadas = getDbTiendasAsMap(conn);
            spTiendas = getSharePointItemsAsMap(listId); // Recargar spTiendas después de posibles actualizaciones de UUID
            logger.debug("Fase 3: Sincronizando tiendas de la Base de Datos a SharePoint...");
            syncDbToSharePoint(conn, listId, dbTiendasActualizadas, spTiendas, result);
            
            conn.commit(); 
            String successMessage = "Sincronización bidireccional completada. " + result.toString();
            logger.info(successMessage); // Loggear el éxito también aquí
            LogUtil.logOperation(conn, "SYNC_TIENDAS", adminUser, successMessage);
            jsonResponse.put("success", true);
            jsonResponse.put("message", successMessage);
        } catch (Exception e) {
            logger.error("Error crítico durante la sincronización de tiendas. Se hará rollback si es posible.", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            jsonResponse.put("success", false);
            jsonResponse.put("message", "Error durante el ciclo de sincronización: " + e.getMessage());
        } finally {
            objectMapper.writeValue(response.getWriter(), jsonResponse);
        }
    }
    
    private void syncSharePointToDb(Connection conn, String listId, Map<String, ListItem> spTiendas, Map<String, TiendaData> dbTiendas, SyncResult result) throws Exception {
        for (ListItem spItem : spTiendas.values()) {
            Map<String, Object> spFields = spItem.getFields().getAdditionalData();
            TiendaData spTiendaData = new TiendaData(spFields); 

            TiendaData existingDbTienda = findTiendaByCodigoInDb(conn, spTiendaData.codigo);

            if (existingDbTienda != null) {
                logger.info("Detectada tienda existente en SharePoint (Código: {}). Actualizando en BD local... Denominacion SP: {}", spTiendaData.codigo, spTiendaData.denominacion);
                spTiendaData.sqlRowUUID = existingDbTienda.sqlRowUUID; 
                updateTiendaInDb(conn, spTiendaData);

                if (spItem.getFields().getAdditionalData().get("SqlRowUUID") == null || 
                    !existingDbTienda.sqlRowUUID.equals(spItem.getFields().getAdditionalData().get("SqlRowUUID"))) {
                    FieldValueSet fieldToUpdate = new FieldValueSet();
                    fieldToUpdate.getAdditionalData().put("SqlRowUUID", existingDbTienda.sqlRowUUID);
                    SharePointUtil.updateListItem(SharePointUtil.SITE_ID, listId, spItem.getId(), fieldToUpdate);
                    logger.debug("UUID de SharePoint actualizado para tienda '{}' (ID SP: {}).", spTiendaData.denominacion, spItem.getId());
                }
                result.spToDbUpdated++;
            } else {
                logger.info("Detectada nueva tienda en SharePoint (Título: \'{}\'). Creando en BD local... Código: {}", spTiendaData.denominacion, spTiendaData.codigo);
                
                String nuevoUuid = UUID.randomUUID().toString();
                spTiendaData.sqlRowUUID = nuevoUuid; 
                insertTiendaInDb(conn, spTiendaData, nuevoUuid);
                
                FieldValueSet fieldToUpdate = new FieldValueSet();
                fieldToUpdate.getAdditionalData().put("SqlRowUUID", nuevoUuid);
                SharePointUtil.updateListItem(SharePointUtil.SITE_ID, listId, spItem.getId(), fieldToUpdate);
                
                logger.info("Tienda '{}' creada en BD con UUID {} y actualizada en SharePoint.", spTiendaData.denominacion, nuevoUuid);
                result.spToDbCreated++;
            }
        }
    }

    private void syncDbToSharePoint(Connection conn, String listId, Map<String, TiendaData> dbTiendas, Map<String, ListItem> spTiendas, SyncResult result) throws Exception {
        for (TiendaData dbTienda : dbTiendas.values()) {
            if (dbTienda.sqlRowUUID == null || dbTienda.sqlRowUUID.isEmpty() || !spTiendas.containsKey(dbTienda.sqlRowUUID)) {
                logger.info("Detectada nueva tienda en BD (Código: {}). Creando en SharePoint... Denominacion DB: {}", dbTienda.codigo, dbTienda.denominacion);

                String uuidParaSp = (dbTienda.sqlRowUUID == null || dbTienda.sqlRowUUID.isEmpty()) ? UUID.randomUUID().toString() : dbTienda.sqlRowUUID;
                
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

    private Map<String, TiendaData> getDbTiendasAsMap(Connection conn) throws SQLException {
        Map<String, TiendaData> map = new HashMap<>();
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM tiendas"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                TiendaData tienda = new TiendaData(rs);
                if (tienda.sqlRowUUID != null && !tienda.sqlRowUUID.isEmpty()) {
                    map.put(tienda.sqlRowUUID, tienda);
                } else {
                    logger.warn("Tienda encontrada en BD sin SqlRowUUID: Codigo = {}. Se debería generar uno en la siguiente sincronización BD->SP.", tienda.codigo);
                }
            }
        }
        return map;
    }

    private Map<String, ListItem> getSharePointItemsAsMap(String listId) throws Exception {
        Map<String, ListItem> map = new HashMap<>();
        ListItemCollectionResponse response = SharePointUtil.getListItems(SharePointUtil.SITE_ID, listId); // CORREGIDO: Uso de getListItems
        
        if (response != null && response.getValue() != null) {
            for (ListItem item : response.getValue()) {
                Map<String, Object> fields = item.getFields().getAdditionalData();
                String uuid = (String) fields.get("SqlRowUUID");
                if (uuid != null && !uuid.isEmpty()) {
                    map.put(uuid, item);
                } else {
                    logger.warn("Item de SharePoint sin SqlRowUUID (ID SP: {}). Se intentará sincronizar por código si existe en BD.", item.getId());
                    map.put("SP_ID_" + item.getId(), item); 
                }
            }
        } else {
            logger.warn("getSharePointItemsAsMap: No se recuperaron ítems de la lista {} en SharePoint.", listId);
        }
        return map;
    }
    
    private TiendaData findTiendaByCodigoInDb(Connection conn, int codigo) throws SQLException {
        String sql = "SELECT * FROM tiendas WHERE codigo = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, codigo);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new TiendaData(rs);
                }
            }
        }
        return null;
    }

    private void insertTiendaInDb(Connection conn, TiendaData tienda, String nuevoUuid) throws SQLException {
        String sql = "INSERT INTO tiendas (codigo, denominacion, SqlRowUUID, Direccion, Lat, Lon, cp, Poblacion, Cadena, prioridad, disponible, HuecosTurno1, HuecosTurno2, HuecosTurno3, HuecosTurno4, Supervisor, Coordinador, Marca, CodZona, Zona, Modalidad) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, tienda.codigo); 
            ps.setString(2, tienda.denominacion);
            ps.setString(3, nuevoUuid);
            ps.setString(4, tienda.direccion);
            ps.setBigDecimal(5, tienda.lat);
            ps.setBigDecimal(6, tienda.lon);
            ps.setString(7, tienda.cp);
            ps.setString(8, tienda.poblacion);
            ps.setString(9, tienda.cadena);
            ps.setString(10, String.format("%04d", tienda.prioridad)); 
            ps.setString(11, tienda.disponible ? "S" : "N");
            ps.setInt(12, tienda.huecos1);
            ps.setInt(13, tienda.huecos2);
            ps.setInt(14, tienda.huecos3);
            ps.setInt(15, tienda.huecos4);
            ps.setString(16, tienda.supervisor); 
            ps.setString(17, tienda.coordinador); 
            ps.setString(18, tienda.marca);
            ps.setString(19, tienda.codZona);
            ps.setString(20, tienda.zona);
            ps.setString(21, tienda.modalidad);
            ps.executeUpdate();
        }
    }

    private void updateTiendaInDb(Connection conn, TiendaData tienda) throws SQLException {
        String sql = "UPDATE tiendas SET denominacion = ?, SqlRowUUID = ?, Direccion = ?, Lat = ?, Lon = ?, cp = ?, Poblacion = ?, Cadena = ?, prioridad = ?, disponible = ?, HuecosTurno1 = ?, HuecosTurno2 = ?, HuecosTurno3 = ?, HuecosTurno4 = ?, Supervisor = ?, Coordinador = ?, Marca = ?, CodZona = ?, Zona = ?, Modalidad = ? WHERE codigo = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tienda.denominacion);
            ps.setString(2, tienda.sqlRowUUID);
            ps.setString(3, tienda.direccion);
            ps.setBigDecimal(4, tienda.lat);
            ps.setBigDecimal(5, tienda.lon);
            ps.setString(6, tienda.cp);
            ps.setString(7, tienda.poblacion);
            ps.setString(8, tienda.cadena);
            ps.setString(9, String.format("%04d", tienda.prioridad));
            ps.setString(10, tienda.disponible ? "S" : "N");
            ps.setInt(11, tienda.huecos1);
            ps.setInt(12, tienda.huecos2);
            ps.setInt(13, tienda.huecos3);
            ps.setInt(14, tienda.huecos4);
            ps.setString(15, tienda.supervisor);
            ps.setString(16, tienda.coordinador);
            ps.setString(17, tienda.marca);
            ps.setString(18, tienda.codZona);
            ps.setString(19, tienda.zona);
            ps.setString(20, tienda.modalidad);
            ps.setInt(21, tienda.codigo); 
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
        fields.getAdditionalData().put("prioridad", String.format("%04d", tienda.prioridad)); 
        fields.getAdditionalData().put("disponible", tienda.disponible);
        fields.getAdditionalData().put("huecosTurno1", tienda.huecos1);
        fields.getAdditionalData().put("huecosTurno2", tienda.huecos2);
        fields.getAdditionalData().put("huecosTurno3", tienda.huecos3);
        fields.getAdditionalData().put("huecosTurno4", tienda.huecos4);
        fields.getAdditionalData().put("SUPERVISOR", tienda.supervisor); 
        fields.getAdditionalData().put("Coordinador", tienda.coordinador); 
        fields.getAdditionalData().put("Marca", tienda.marca);
        fields.getAdditionalData().put("CodZona", tienda.codZona);
        fields.getAdditionalData().put("Zona", tienda.zona);
        fields.getAdditionalData().put("Modalidad", tienda.modalidad);
        fields.getAdditionalData().put("SqlRowUUID", uuid);
        return fields;
    }
}
