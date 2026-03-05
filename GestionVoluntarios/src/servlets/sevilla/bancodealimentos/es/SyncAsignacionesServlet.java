package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
import util.sevilla.bancodealimentos.es.SharePointUtil;
import util.sevilla.bancodealimentos.es.LogUtil;

@WebServlet("/sync-asignaciones")
public class SyncAsignacionesServlet extends HttpServlet {
    private static final long serialVersionUID = 3L; // Versión incrementada
    
    private static final Logger logger = LoggerFactory.getLogger(SyncAsignacionesServlet.class);
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    private static final String SP_LIST_ASIGNACIONES = "Asignaciones";
    private static final String SP_LIST_VOLUNTARIOS = "Voluntarios";
    private static final String SP_LIST_TIENDAS = "Tiendas";

    private boolean isAdmin(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("usuario") == null) return false;
        
        Object isAdminAttr = session.getAttribute("isAdmin");
        return (isAdminAttr instanceof Boolean && (Boolean) isAdminAttr) || 
               ("S".equals(isAdminAttr));
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        if (!isAdmin(request)) {
            logger.warn("Acceso denegado a SyncAsignaciones. IP: {}", request.getRemoteAddr());
            sendJsonResponse(response, HttpServletResponse.SC_FORBIDDEN, false, "Acceso denegado. Permisos insuficientes.", "");
            return;
        }

        StringBuilder errorLog = new StringBuilder();
        int fallidos = 0;
        int totalProcesados = 0;

        logger.info("Iniciando sincronización masiva de asignaciones con SharePoint...");

        try (Connection conn = DatabaseUtil.getConnection()) {
            String listIdAsignaciones = SharePointUtil.getListId(SharePointUtil.SITE_ID, SP_LIST_ASIGNACIONES);
            String listIdTiendas = SharePointUtil.getListId(SharePointUtil.SITE_ID, SP_LIST_TIENDAS);
            String listIdVoluntarios = SharePointUtil.getListId(SharePointUtil.SITE_ID, SP_LIST_VOLUNTARIOS);

            if (listIdAsignaciones == null) throw new Exception("Lista 'Asignaciones' no encontrada en SP.");
            if (listIdTiendas == null) throw new Exception("Lista 'Tiendas' no encontrada en SP.");
            if (listIdVoluntarios == null) throw new Exception("Lista 'Voluntarios' no encontrada en SP.");

            logger.info("Eliminando asignaciones antiguas en SharePoint...");
            SharePointUtil.deleteAllListItems(SharePointUtil.SITE_ID, listIdAsignaciones);
            Thread.sleep(1000); 

            String sql = "SELECT * FROM voluntarios_en_campana";
            try (PreparedStatement psVoluntariosCampana = conn.prepareStatement(sql);
                 ResultSet rs = psVoluntariosCampana.executeQuery()) {

                while (rs.next()) {
                    totalProcesados++;
                    String voluntarioRowUuid = rs.getString("SqlRowUUID"); 
                    
                    if (voluntarioRowUuid == null) voluntarioRowUuid = "AS-" + rs.getString("Usuario"); 
                    
                    String idVoluntarioDb = rs.getString("Usuario");

                    try {
                        FieldValueSet fields = new FieldValueSet();
                        fields.getAdditionalData().put("SqlRowUUID", voluntarioRowUuid);
                        fields.getAdditionalData().put("Title", voluntarioRowUuid);

                        String voluntarioUuid = getLookupUuid(conn, "SELECT SqlRowUUID FROM voluntarios WHERE usuario = ?", idVoluntarioDb);
                        
                        if (voluntarioUuid != null) {
                            String spVoluntarioId = SharePointUtil.findItemIdByFieldValue(conn, SharePointUtil.SITE_ID, listIdVoluntarios, "SqlRowUUID", voluntarioUuid);
                            if (spVoluntarioId != null) {
                                fields.getAdditionalData().put("UsuarioLookupId", spVoluntarioId);
                            } else {
                                throw new Exception("Voluntario UUID " + voluntarioUuid + " no encontrado en lista SP 'Voluntarios'.");
                            }
                        } else {
                            throw new Exception("Voluntario ID " + idVoluntarioDb + " no encontrado en BD local o sin UUID.");
                        }
                        
                        fields.getAdditionalData().put("Campana", rs.getString("Campana"));

                        // --- PROCESAR TURNOS (Lookups de Tiendas) ---
                        boolean tieneAlgunoTurno = false; 
                        for (int i = 1; i <= 4; i++) {
                            int idTiendaDb = rs.getInt("Turno" + i);
                            String comentario = rs.getString("Comentario" + i);

                            if (idTiendaDb > 0 && !rs.wasNull()) {
                                tieneAlgunoTurno = true;
                                String tiendaUuid = getLookupUuid(conn, "SELECT SqlRowUUID FROM tiendas WHERE codigo = ?", idTiendaDb);
                                
                                if (tiendaUuid != null) {
                                    String spTiendaId = SharePointUtil.findItemIdByFieldValue(conn, SharePointUtil.SITE_ID, listIdTiendas, "SqlRowUUID", tiendaUuid);
                                    if (spTiendaId != null) {
                                        fields.getAdditionalData().put("Turno" + i + "LookupId", spTiendaId);
                                        fields.getAdditionalData().put("Comentario" + i, comentario);
                                        
                                        // --- NUEVO: Guardar código formateado si existe el campo ---
                                        String codigoFormateado = String.format("%03d", idTiendaDb);
                                        fields.getAdditionalData().put("Turno" + i + "Codigo", codigoFormateado);
                                    } else {
                                        logger.warn("Tienda UUID {} no encontrada en SP. Turno {} quedará vacío.", tiendaUuid, i);
                                        errorLog.append("WARN: Tienda UUID ").append(tiendaUuid).append(" no en SP (Turno ").append(i).append(").\n");
                                    }
                                } else {
                                    errorLog.append("WARN: Tienda ID ").append(idTiendaDb).append(" no encontrada en BD local (Turno ").append(i).append(").\n");
                                    throw new Exception("Tienda ID " + idTiendaDb + " no encontrada en BD local.");
                                }
                            }
                        }
                        
                        // Solo crear si tiene al menos un turno
                        if (tieneAlgunoTurno) {
                            SharePointUtil.createListItem(SharePointUtil.SITE_ID, listIdAsignaciones, fields);
                        }

                        if (totalProcesados % 10 == 0) Thread.sleep(200);

                    } catch (Exception e) {
                        fallidos++;
                        String msg = "Error procesando asignación para voluntario " + idVoluntarioDb + ": " + e.getMessage();
                        logger.error(msg);
                        errorLog.append("[FALLO] ").append(msg).append("\n");
                    }
                }
            }
            
            String message = String.format("Sincronización de Asignaciones completada. Total: %d. Fallidos: %d.", totalProcesados, fallidos);
            logger.info(message);
            
            sendJsonResponse(response, HttpServletResponse.SC_OK, fallidos == 0, message, errorLog.toString());
            
        } catch (Exception e) {
            logger.error("Error CRÍTICO en SyncAsignacionesServlet", e);
            sendJsonResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, false, "Error crítico: " + e.getMessage(), "");
        }
    }

    private String getLookupUuid(Connection conn, String sql, Object param) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (param instanceof Integer) {
                ps.setInt(1, (Integer) param);
            } else {
                ps.setString(1, (String) param);
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
    
    private void sendJsonResponse(HttpServletResponse response, int statusCode, boolean success, String message, String errorLog) throws IOException {
        if (!response.isCommitted()) {
            response.setStatus(statusCode);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            
            Map<String, Object> jsonResponse = new HashMap<>();
            jsonResponse.put("success", success);
            jsonResponse.put("message", message);
            if (errorLog != null && !errorLog.isEmpty()) {
                jsonResponse.put("errors", errorLog);
            }
            
            objectMapper.writeValue(response.getWriter(), jsonResponse);
        }
    }
}