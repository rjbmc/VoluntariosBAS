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

import util.sevilla.bancodealimentos.es.DatabaseUtil;
import util.sevilla.bancodealimentos.es.LogUtil;
import util.sevilla.bancodealimentos.es.SharepointUtil;

@WebServlet("/confirmar-cambio-email")
public class ConfirmarCambioEmailServlet extends HttpServlet {
    private static final long serialVersionUID = 3L;
    
    // 1. Logger SLF4J
    private static final Logger logger = LoggerFactory.getLogger(ConfirmarCambioEmailServlet.class);
    
    // 2. Jackson ObjectMapper
    private final ObjectMapper mapper = new ObjectMapper();
    
    private static final String SP_LIST_NAME = "Voluntarios";
    private static final String SP_UUID_FIELD = "SqlRowUUID";
    // IMPORTANTE: Verificar si el nombre interno en SharePoint es "Email" o "field_6" (como en AdminVoluntarios)
    private static final String SP_EMAIL_FIELD = "Email"; 

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String token = request.getParameter("token");

        if (token == null || token.trim().isEmpty()) {
            logger.warn("Intento de confirmación de email sin token. IP: {}", request.getRemoteAddr());
            sendJsonResponse(response, false, "Token no proporcionado.", HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        Connection conn = null;
        try {
            conn = DatabaseUtil.getConnection();
            conn.setAutoCommit(false);
            
            String usuario = null;
            String nuevoEmail = null;
            String sqlRowUuid = null;

            String sqlFindUser = "SELECT Usuario, nuevo_email, SqlRowUUID FROM voluntarios WHERE token_cambio_email = ?";
            try (PreparedStatement stmtFind = conn.prepareStatement(sqlFindUser)) {
                stmtFind.setString(1, token);
                try (ResultSet rs = stmtFind.executeQuery()) {
                    if (rs.next()) {
                        usuario = rs.getString("Usuario");
                        nuevoEmail = rs.getString("nuevo_email");
                        sqlRowUuid = rs.getString("SqlRowUUID");
                    }
                }
            }

            if (usuario != null && nuevoEmail != null) {
                // Actualizar DB local
                String sqlUpdate = "UPDATE voluntarios SET Email = ?, nuevo_email = NULL, token_cambio_email = NULL, notificar = 'S' WHERE Usuario = ?";
                try (PreparedStatement stmtUpdate = conn.prepareStatement(sqlUpdate)) {
                    stmtUpdate.setString(1, nuevoEmail);
                    stmtUpdate.setString(2, usuario);
                    stmtUpdate.executeUpdate();
                }
                
                LogUtil.logOperation(conn, "CAMBIO_EMAIL_OK", usuario, "Email actualizado en BBDD a: " + nuevoEmail);

                // Replicación a SharePoint
                if (sqlRowUuid != null) {
                    try {
                        String listId = SharepointUtil.getListId(SharepointUtil.SP_SITE_ID_VOLUNTARIOS, SP_LIST_NAME);
                        
                        if (listId == null) {
                            logger.error("Lista SharePoint '{}' no encontrada.", SP_LIST_NAME);
                            // No lanzamos excepción para no revertir el cambio local, solo logueamos el fallo de sync
                        } else {
                            String itemId = SharepointUtil.findItemIdByFieldValue(SharepointUtil.SP_SITE_ID_VOLUNTARIOS, listId, SP_UUID_FIELD, sqlRowUuid);
                            
                            if (itemId != null) {
                                Map<String, Object> spData = new HashMap<>();
                                spData.put(SP_EMAIL_FIELD, nuevoEmail);
                                
                                FieldValueSet fieldsToUpdate = new FieldValueSet();
                                fieldsToUpdate.setAdditionalData(spData);

                                SharepointUtil.updateListItem(SharepointUtil.SP_SITE_ID_VOLUNTARIOS, listId, itemId, fieldsToUpdate);
                                LogUtil.logOperation(conn, "SP_EMAIL_UPDATE_OK", usuario, "Email replicado a SharePoint: " + nuevoEmail);
                            } else {
                                logger.warn("No se encontró item en SharePoint para el voluntario {} (UUID: {})", usuario, sqlRowUuid);
                                LogUtil.logOperation(conn, "SP_EMAIL_UPDATE_WARN", usuario, "No se encontró item en SP para UUID: " + sqlRowUuid);
                            }
                        }
                    } catch (Exception e) {
                        logger.error("Fallo al replicar cambio de email a SharePoint para {}", usuario, e);
                        LogUtil.logOperation(conn, "SP_EMAIL_UPDATE_ERROR", usuario, "Fallo al replicar cambio de email a SharePoint: " + e.getMessage());
                    }
                } else {
                    logger.warn("Usuario {} no tiene SqlRowUUID, se omite replicación a SharePoint.", usuario);
                    LogUtil.logOperation(conn, "SP_EMAIL_UPDATE_WARN", usuario, "No se encontró SqlRowUUID para replicar el cambio de email a SharePoint.");
                }

                conn.commit();
                logger.info("Cambio de email confirmado exitosamente para {}", usuario);
                sendJsonResponse(response, true, "Tu dirección de correo ha sido actualizada con éxito.", HttpServletResponse.SC_OK);
            } else {
                conn.rollback();
                logger.warn("Intento de confirmación con token inválido: {}", token);
                sendJsonResponse(response, false, "El enlace de confirmación no es válido o ya ha sido utilizado.", HttpServletResponse.SC_BAD_REQUEST);
            }

        } catch (SQLException e) {
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) { logger.warn("Fallo en rollback", ex); }
            
            String message;
            if (e.getErrorCode() == 1062) { // Error de clave duplicada
                logger.warn("Conflicto: El nuevo email ya existe en la BD. Token: {}", token);
                message = "La nueva dirección de correo ya está en uso por otro voluntario.";
            } else {
                logger.error("Error SQL al confirmar cambio de email", e);
                message = "Error de base de datos al actualizar el email.";
            }
            sendJsonResponse(response, false, message, HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException e) { logger.warn("Error cerrando conexión", e); }
        }
    }

    private void sendJsonResponse(HttpServletResponse response, boolean success, String message, int statusCode) throws IOException {
        response.setStatus(statusCode);
        Map<String, Object> json = new HashMap<>();
        json.put("success", success);
        json.put("message", message);
        mapper.writeValue(response.getWriter(), json);
    }
}