package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.graph.models.FieldValueSet;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import util.sevilla.bancodealimentos.es.DatabaseUtil;
import util.sevilla.bancodealimentos.es.LogUtil;
import util.sevilla.bancodealimentos.es.SharePointUtil;

@WebServlet("/sync-voluntarios")
public class SyncVoluntariosServlet extends HttpServlet {
    private static final long serialVersionUID = 2L;
    
    // 1. Logger SLF4J
    private static final Logger logger = LoggerFactory.getLogger(SyncVoluntariosServlet.class);
    
    // 2. Jackson ObjectMapper
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    private static final String SHAREPOINT_LIST_NAME = "Voluntarios";

    // 3. Verificación de seguridad estandarizada
    private boolean isAdmin(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("usuario") == null) return false;
        
        Object isAdminAttr = session.getAttribute("isAdmin");
        return (isAdminAttr instanceof Boolean && (Boolean) isAdminAttr) || 
               ("S".equals(isAdminAttr));
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        if (!isAdmin(request)) {
            logger.warn("Acceso denegado a SyncVoluntarios. IP: {}", request.getRemoteAddr());
            sendJsonResponse(response, HttpServletResponse.SC_FORBIDDEN, false, "Acceso denegado.");
            return;
        }

        String adminUser = (String) request.getSession(false).getAttribute("usuario");
        logger.info("Iniciando sincronización masiva de voluntarios. Usuario: {}", adminUser);

        Connection conn = null;
        try {
            conn = DatabaseUtil.getConnection();
            
            // Usamos SITE_ID o SP_SITE_ID_VOLUNTARIOS según configuración (aquí usamos el estándar SITE_ID)
            String listId = SharePointUtil.getListId(SharePointUtil.SITE_ID, SHAREPOINT_LIST_NAME);
            
            if (listId == null) {
                logger.error("Lista SharePoint '{}' no encontrada.", SHAREPOINT_LIST_NAME);
                throw new Exception("La lista '" + SHAREPOINT_LIST_NAME + "' no fue encontrada en SharePoint.");
            }

            logger.info("Limpiando lista '{}' en SharePoint...", SHAREPOINT_LIST_NAME);
            SharePointUtil.deleteAllListItems(SharePointUtil.SITE_ID, listId);

            String sql = "SELECT Nombre, Apellidos, `DNI NIF`, tiendaReferencia, Email, telefono, fechaNacimiento, cp, administrador, verificado, SqlRowUUID FROM voluntarios";
            
            logger.debug("Comenzando carga de voluntarios a SharePoint...");
            
            int procesados = 0;
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                
                while(rs.next()){
                    FieldValueSet fields = new FieldValueSet();

                    String nombre = rs.getString("Nombre");
                    String apellidos = rs.getString("Apellidos");
                    String title = ((nombre != null ? nombre : "") + " " + (apellidos != null ? apellidos : "")).trim();
                    if (title.isEmpty()) { title = "Voluntario " + rs.getString("SqlRowUUID"); }
                    
                    fields.getAdditionalData().put("Title", title);
                    fields.getAdditionalData().put("field_1", nombre);
                    fields.getAdditionalData().put("field_2", apellidos);
                    fields.getAdditionalData().put("field_3", rs.getString("DNI NIF"));
                    
                    int tiendaRef = rs.getInt("tiendaReferencia");
                    if (!rs.wasNull()) {
                        fields.getAdditionalData().put("field_5", tiendaRef);
                    }
                    
                    fields.getAdditionalData().put("field_6", rs.getString("Email"));
                    fields.getAdditionalData().put("field_7", rs.getString("telefono"));

                    java.sql.Date fechaNac = rs.getDate("fechaNacimiento");
                    if (fechaNac != null) {
                        fields.getAdditionalData().put("field_8", fechaNac.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE));
                    }

                    String cp_str = rs.getString("cp");
                    if (cp_str != null && !cp_str.trim().isEmpty()) {
                        try {
                            fields.getAdditionalData().put("field_9", Integer.parseInt(cp_str.trim()));
                        } catch (NumberFormatException e) {
                            logger.debug("CP no numérico ignorado para SharePoint: {}", cp_str);
                        }
                    }

                    fields.getAdditionalData().put("administrador", "S".equalsIgnoreCase(rs.getString("administrador")));
                    fields.getAdditionalData().put("Verificado", "S".equalsIgnoreCase(rs.getString("verificado")));
                    fields.getAdditionalData().put("SqlRowUUID", rs.getString("SqlRowUUID"));

                    SharePointUtil.createListItem(SharePointUtil.SITE_ID, listId, fields);
                    procesados++;
                    
                    // Pausa leve para evitar throttling en cargas masivas
                    if (procesados % 50 == 0) {
                        logger.debug("Sincronizados {} voluntarios...", procesados);
                        Thread.sleep(200); 
                    }
                }
            }
            
            logger.info("Sincronización finalizada. Total voluntarios procesados: {}", procesados);
            LogUtil.logOperation(conn, "SYNC_VOLUNTARIOS", adminUser, "Sincronización masiva de " + procesados + " voluntarios completada.");
            
            sendJsonResponse(response, HttpServletResponse.SC_OK, true, "Sincronización de Voluntarios completada con éxito (" + procesados + " registros).");

        } catch (Exception e) {
            logger.error("Error crítico en la sincronización de Voluntarios", e);
            sendJsonResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, false, "Error en la sincronización: " + e.getMessage());
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException e) { logger.warn("Error cerrando conexión", e); }
        }
    }
    
    private void sendJsonResponse(HttpServletResponse response, int statusCode, boolean success, String message) throws IOException {
        if (!response.isCommitted()) {
            response.setStatus(statusCode);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            
            Map<String, Object> jsonResponse = new HashMap<>();
            jsonResponse.put("success", success);
            jsonResponse.put("message", message);
            
            objectMapper.writeValue(response.getWriter(), jsonResponse);
        }
    }
}