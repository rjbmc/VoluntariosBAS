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

@WebServlet("/sync-campanas")
public class SyncCampanasServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    
    // 1. Logger SLF4J
    private static final Logger logger = LoggerFactory.getLogger(SyncCampanasServlet.class);
    
    // 2. Jackson ObjectMapper
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    private static final String SHAREPOINT_LIST_NAME = "Campanas";

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        Map<String, Object> jsonResponse = new HashMap<>();
        HttpSession session = request.getSession(false);

        // 3. Verificación de seguridad estandarizada (Busca "S" como en los otros servlets)
        boolean isAdmin = session != null && 
                          session.getAttribute("usuario") != null && 
                          "S".equals(session.getAttribute("isAdmin"));

        if (!isAdmin) {
            String ip = request.getRemoteAddr();
            logger.warn("Acceso denegado a SyncCampanas. Usuario: {}, IP: {}", 
                        (session != null ? session.getAttribute("usuario") : "Anónimo"), ip);
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            jsonResponse.put("success", false);
            jsonResponse.put("message", "Acceso denegado. Permisos insuficientes.");
            objectMapper.writeValue(response.getWriter(), jsonResponse);
            return;
        }

        String adminUser = (String) session.getAttribute("usuario");
        logger.info("Iniciando sincronización de campañas con SharePoint. Usuario: {}", adminUser);

        Connection conn = null;
        try {
            conn = DatabaseUtil.getConnection();
            
            // CORRECCIÓN: Usamos SharePointUtil.SITE_ID en lugar de SITE_ID_VOLUNTARIOS
            // Asumimos que SITE_ID apunta al sitio correcto donde está la lista "Campanas"
            String listId = SharePointUtil.getListId(SharePointUtil.SITE_ID, SHAREPOINT_LIST_NAME); 
            
            if (listId == null) {
                logger.error("No se encontró la lista '{}' en el sitio de SharePoint.", SHAREPOINT_LIST_NAME);
                throw new Exception("La lista '" + SHAREPOINT_LIST_NAME + "' no fue encontrada en SharePoint.");
            }

            logger.debug("Limpiando lista de SharePoint (ID: {})...", listId);
            SharePointUtil.deleteAllListItems(SharePointUtil.SITE_ID, listId);

            String sql = "SELECT SqlRowUUID, denominacion, Campana, fecha1, fecha2, estado, Comentarios, turnospordia FROM campanas";
            
            logger.debug("Consultando base de datos y preparando subida...");
            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                
                int count = 0;
                while(rs.next()){
                    FieldValueSet fields = new FieldValueSet();

                    // Mapeo de campos
                    fields.getAdditionalData().put("SqlRowUUID", rs.getString("SqlRowUUID"));
                    fields.getAdditionalData().put("Title", rs.getString("denominacion"));
                    fields.getAdditionalData().put("nombre", rs.getString("Campana"));
                    
                    String estado_str = rs.getString("estado");
                    boolean estado_bool = "S".equalsIgnoreCase(estado_str);
                    fields.getAdditionalData().put("activa", estado_bool);
                    
                    fields.getAdditionalData().put("texto_consentimiento", rs.getString("Comentarios"));
                    
                    int turnos = rs.getInt("turnospordia");
                    if(!rs.wasNull()){
                        fields.getAdditionalData().put("TurnosPorDia", turnos);
                    }

                    java.sql.Date fecha1_sql = rs.getDate("fecha1");
                    if(fecha1_sql != null){
                        fields.getAdditionalData().put("fecha_inicio", fecha1_sql.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE));
                    }

                    java.sql.Date fecha2_sql = rs.getDate("fecha2");
                    if(fecha2_sql != null){
                        fields.getAdditionalData().put("fecha_fin", fecha2_sql.toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE));
                    }

                    // Creación del ítem en SharePoint usando SITE_ID
                    SharePointUtil.createListItem(SharePointUtil.SITE_ID, listId, fields);
                    count++;
                }
                logger.info("Sincronización finalizada. {} campañas procesadas.", count);
            }

            jsonResponse.put("success", true);
            jsonResponse.put("message", "¡Éxito! Sincronización de Campañas completada.");
            
            LogUtil.logOperation(conn, "SYNC_CAMPANAS", adminUser, "Sincronización de Campañas completada con éxito.");

        } catch (Exception e) {
            logger.error("Error crítico durante la sincronización de campañas", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            jsonResponse.put("success", false);
            jsonResponse.put("message", "Error en la sincronización: " + e.getMessage());
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException e) { logger.warn("Error cerrando conexión", e); }
            
            // Respuesta final con Jackson
            objectMapper.writeValue(response.getWriter(), jsonResponse);
        }
    }
}