package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
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

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import util.sevilla.bancodealimentos.es.DatabaseUtil;
import util.sevilla.bancodealimentos.es.LogUtil;
import util.sevilla.bancodealimentos.es.SharePointUtil;

@WebServlet("/verificar-email")
public class VerificarEmailServlet extends HttpServlet {
    private static final long serialVersionUID = 3L;
    
    // 1. Logger SLF4J
    private static final Logger logger = LoggerFactory.getLogger(VerificarEmailServlet.class);
    
    // 2. Jackson ObjectMapper
    private final ObjectMapper mapper = new ObjectMapper();
    
    private static final String SP_LIST_NAME = "Voluntarios";
    private static final String SP_UUID_FIELD = "SqlRowUUID";
    private static final String SP_VERIFIED_FIELD = "Verificado"; 

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String token = request.getParameter("token");

        if (token == null || token.isEmpty()) {
            logger.warn("Intento de verificación de email sin token. IP: {}", request.getRemoteAddr());
            sendJsonResponse(response, false, "Token de verificación no proporcionado.", HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        Connection conn = null;
        try {
            conn = DatabaseUtil.getConnection();
            conn.setAutoCommit(false);
            
            String usuario = null;
            String sqlRowUuid = null;

            // Buscar usuario por token y que NO esté verificado aún
            String findSql = "SELECT Usuario, SqlRowUUID FROM voluntarios WHERE token_verificacion = ? AND verificado = 'N'";
            try (PreparedStatement stmt = conn.prepareStatement(findSql)) {
                stmt.setString(1, token);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        usuario = rs.getString("Usuario");
                        sqlRowUuid = rs.getString("SqlRowUUID");
                    }
                }
            }

            if (usuario != null) {
                // Si por alguna razón no tenía UUID (registro antiguo), le asignamos uno ahora
                boolean uuidGeneradoAhora = false;
                if (sqlRowUuid == null || sqlRowUuid.isEmpty()) {
                    sqlRowUuid = UUID.randomUUID().toString();
                    uuidGeneradoAhora = true;
                }

                // Actualizar DB local
                String updateSql = "UPDATE voluntarios SET verificado = 'S', token_verificacion = NULL, SqlRowUUID = ?, notificar = 'S' WHERE Usuario = ?";
                try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                    updateStmt.setString(1, sqlRowUuid);
                    updateStmt.setString(2, usuario);
                    updateStmt.executeUpdate();
                }
                
                LogUtil.logOperation(conn, "EMAIL_VERIFIED", usuario, "Email verificado correctamente con token.");

                // Replicar a SharePoint (Best effort)
                try {
                    String listId = SharePointUtil.getListId(SharePointUtil.SP_SITE_ID_VOLUNTARIOS, SP_LIST_NAME);
                    if (listId != null) {
                        String itemId = null;
                        
                        // Si ya tenía UUID, intentamos buscarlo por UUID
                        if (!uuidGeneradoAhora) {
                            // ¡CORREGIDO! Pasar 'conn' a findItemIdByFieldValue
                            itemId = SharePointUtil.findItemIdByFieldValue(conn, SharePointUtil.SP_SITE_ID_VOLUNTARIOS, listId, SP_UUID_FIELD, sqlRowUuid);
                        }
                        
                        if (itemId != null) {
                            Map<String, Object> spData = new HashMap<>();
                            spData.put(SP_VERIFIED_FIELD, true); 
                            
                            FieldValueSet fields = new FieldValueSet();
                            fields.setAdditionalData(spData);
                            
                            SharePointUtil.updateListItem(SharePointUtil.SP_SITE_ID_VOLUNTARIOS, listId, itemId, fields);
                            logger.info("Estado de verificación replicado a SharePoint para usuario {}", usuario);
                        } else {
                            logger.warn("Usuario {} verificado localmente, pero no encontrado en SharePoint (UUID: {}). Se sincronizará en el próximo barrido.", usuario, sqlRowUuid);
                        }
                    } else {
                        logger.error("Lista SharePoint '{}' no encontrada durante verificación.", SP_LIST_NAME);
                    }
                } catch (Exception e) {
                    logger.error("Error al replicar verificación de email a SharePoint para {}", usuario, e);
                    LogUtil.logOperation(conn, "SP_VERIFY_ERROR", usuario, "Error al replicar verificación: " + e.getMessage());
                }

                conn.commit();
                logger.info("Cuenta verificada exitosamente: {}", usuario);
                
                sendJsonResponse(response, true, "¡Gracias por verificar tu correo electrónico! Ya puedes iniciar sesión.", HttpServletResponse.SC_OK);

            } else {
                conn.rollback();
                logger.warn("Intento de verificación fallido. Token inválido o cuenta ya verificada: {}", token);
                sendJsonResponse(response, false, "El enlace de verificación no es válido o tu correo ya ha sido verificado.", HttpServletResponse.SC_BAD_REQUEST);
            }

        } catch (SQLException e) {
            logger.error("Error SQL durante verificación de email", e);
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) { logger.warn("Rollback fallido", ex); }
            sendJsonResponse(response, false, "Error de base de datos durante la verificación.", HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException e) { logger.warn("Error cerrando conexión", e); }
        }
    }
    
    private void sendJsonResponse(HttpServletResponse response, boolean success, String message, int statusCode) throws IOException {
        if (!response.isCommitted()) {
            response.setStatus(statusCode);
            Map<String, Object> jsonResponse = new HashMap<>();
            jsonResponse.put("success", success);
            jsonResponse.put("message", message);
            mapper.writeValue(response.getWriter(), jsonResponse);
        }
    }
}
