package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.graph.models.FieldValueSet;
import com.microsoft.graph.models.ListItem;
import com.microsoft.graph.models.ListItemCollectionResponse;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import util.sevilla.bancodealimentos.es.DatabaseUtil;
import util.sevilla.bancodealimentos.es.LogUtil;
import util.sevilla.bancodealimentos.es.SharepointUtil;

@WebServlet("/sync-tiendas")
public class SyncTiendasServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    
    // 1. Logger SLF4J para registrar la actividad
    private static final Logger logger = LoggerFactory.getLogger(SyncTiendasServlet.class);
    
    // 2. Jackson ObjectMapper para respuestas JSON
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    private static final String SHAREPOINT_LIST_NAME = "Tiendas";
    private static final int PAUSA_ENTRE_PETICIONES_MS = 400; // Anti-throttling para SharePoint

    // Clase interna para almacenar datos de la tienda en memoria
    private static class TiendaData {
        int codigo; String denominacion; String sqlRowUUID;
        String direccion; BigDecimal lat; BigDecimal lon; String cp; String poblacion; String cadena; 
        int prioridad; boolean disponible; int huecos1, huecos2, huecos3, huecos4;

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
            this.prioridad = rs.getInt("prioridad");
            this.disponible = "S".equalsIgnoreCase(rs.getString("disponible"));
            this.huecos1 = rs.getInt("HuecosTurno1");
            this.huecos2 = rs.getInt("HuecosTurno2");
            this.huecos3 = rs.getInt("HuecosTurno3");
            this.huecos4 = rs.getInt("HuecosTurno4");
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        Map<String, Object> jsonResponse = new HashMap<>();
        HttpSession session = request.getSession(false);

        // 3. Verificación de seguridad estandarizada
        boolean isAdmin = session != null && 
                          session.getAttribute("usuario") != null && 
                          "S".equals(session.getAttribute("isAdmin"));

        if (!isAdmin) {
            String ip = request.getRemoteAddr();
            logger.warn("Acceso denegado a SyncTiendas. Usuario: {}, IP: {}", 
                        (session != null ? session.getAttribute("usuario") : "Anónimo"), ip);
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            jsonResponse.put("success", false);
            jsonResponse.put("message", "Acceso denegado. Permisos insuficientes.");
            objectMapper.writeValue(response.getWriter(), jsonResponse);
            return;
        }

        String adminUser = (String) session.getAttribute("usuario");
        logger.info("Iniciando sincronización masiva de tiendas. Solicitado por: {}", adminUser);

        List<TiendaData> tiendasEnMemoria = new ArrayList<>();
        Set<String> uuidsEsperados = new HashSet<>();

        // Paso 1: Cargar datos desde la base de datos local
        try (Connection conn = DatabaseUtil.getConnection()) {
            String sql = "SELECT * FROM tiendas WHERE SqlRowUUID IS NOT NULL";
            try (PreparedStatement ps = conn.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TiendaData tienda = new TiendaData(rs);
                    tiendasEnMemoria.add(tienda);
                    uuidsEsperados.add(tienda.sqlRowUUID);
                }
            }
            logger.info("Cargadas {} tiendas desde la base de datos local.", tiendasEnMemoria.size());
        } catch (Exception e) {
            logger.error("Error crítico al leer tiendas desde la base de datos", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            jsonResponse.put("success", false);
            jsonResponse.put("message", "Error crítico al leer desde la base de datos: " + e.getMessage());
            objectMapper.writeValue(response.getWriter(), jsonResponse);
            return;
        }

        // Paso 2: Sincronizar con SharePoint
        Connection logConn = null;
        try {
            // Obtener ID de la lista en SharePoint
            String listId = SharepointUtil.getListId(SharepointUtil.SITE_ID, SHAREPOINT_LIST_NAME);
            if (listId == null) {
                logger.error("La lista '{}' no existe en el sitio de SharePoint.", SHAREPOINT_LIST_NAME);
                throw new Exception("La lista '" + SHAREPOINT_LIST_NAME + "' no fue encontrada.");
            }

            // Borrado previo de la lista
            logger.info("Limpiando lista de SharePoint (ID: {})...", listId);
            SharepointUtil.deleteAllListItems(SharepointUtil.SITE_ID, listId);

            // Recreación de ítems
            logger.info("Iniciando creación de {} elementos en SharePoint...", tiendasEnMemoria.size());
            for (int i = 0; i < tiendasEnMemoria.size(); i++) {
                TiendaData tienda = tiendasEnMemoria.get(i);
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
                fields.getAdditionalData().put("prioridad", tienda.prioridad);
                fields.getAdditionalData().put("disponible", tienda.disponible);
                fields.getAdditionalData().put("huecosTurno1", tienda.huecos1);
                fields.getAdditionalData().put("huecosTurno2", tienda.huecos2);
                fields.getAdditionalData().put("huecosTurno3", tienda.huecos3);
                fields.getAdditionalData().put("huecosTurno4", tienda.huecos4);
                fields.getAdditionalData().put("SqlRowUUID", tienda.sqlRowUUID);
                
                SharepointUtil.createListItem(SharepointUtil.SITE_ID, listId, fields);
                
                // Pausa para evitar Throttling de Microsoft Graph (Error 429)
                Thread.sleep(PAUSA_ENTRE_PETICIONES_MS);
                
                // Log de progreso cada 20 ítems para no saturar el log
                if ((i + 1) % 20 == 0) {
                    logger.debug("Progreso sincronización: {}/{} tiendas procesadas.", i + 1, tiendasEnMemoria.size());
                }
            }

            logger.info("Creación finalizada. Pausando 10 segundos para propagación antes de verificar...");
            Thread.sleep(10000);

            // Paso 3: Verificación post-sincronización
            Set<String> uuidsReales = new HashSet<>();
            ListItemCollectionResponse itemsPostSync = SharepointUtil.getListItems(SharepointUtil.SITE_ID, listId);
            if (itemsPostSync != null && itemsPostSync.getValue() != null) {
                for (ListItem item : itemsPostSync.getValue()) {
                    Map<String, Object> fields = item.getFields().getAdditionalData();
                    if (fields.containsKey("SqlRowUUID")) {
                        Object uuidObj = fields.get("SqlRowUUID");
                        if (uuidObj instanceof String) {
                            uuidsReales.add((String) uuidObj);
                        }
                    }
                }
            }

            if (uuidsReales.size() < uuidsEsperados.size()) {
                 String msg = "FALLO DE VERIFICACIÓN: Se esperaban " + uuidsEsperados.size() + " tiendas, pero solo se encontraron " + uuidsReales.size() + " en SharePoint. Posible problema de 'throttling'.";
                 logger.warn(msg);
                 jsonResponse.put("success", false);
                 jsonResponse.put("message", msg);
            } else {
                String successMessage = "¡Éxito verificado! Se han sincronizado " + uuidsReales.size() + " tiendas correctamente.";
                logger.info(successMessage);
                
                logConn = DatabaseUtil.getConnection();
                LogUtil.logOperation(logConn, "SYNC_TIENDAS", adminUser, "Sincronización de Tiendas completada con éxito.");
                
                jsonResponse.put("success", true);
                jsonResponse.put("message", successMessage);
            }

        } catch (Exception e) {
            logger.error("Error crítico durante la sincronización de tiendas", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            jsonResponse.put("success", false);
            jsonResponse.put("message", "Error durante el ciclo de sincronización: " + e.getMessage());
        } finally {
            if (logConn != null) try { logConn.close(); } catch (SQLException e) { logger.warn("Error cerrando conexión de log", e); }
            
            // Respuesta final con Jackson
            objectMapper.writeValue(response.getWriter(), jsonResponse);
        }
    }
}