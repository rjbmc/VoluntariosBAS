package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import util.sevilla.bancodealimentos.es.DatabaseUtil;
import util.sevilla.bancodealimentos.es.LogUtil;
import util.sevilla.bancodealimentos.es.SharepointReplicationUtil;
import util.sevilla.bancodealimentos.es.SharePointUtil;

@WebServlet("/confirmar-baja")
public class ConfirmarBajaServlet extends HttpServlet {
    private static final long serialVersionUID = 4L;
    
    // 1. Logger SLF4J
    private static final Logger logger = LoggerFactory.getLogger(ConfirmarBajaServlet.class);
    
    // 2. Jackson ObjectMapper
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String token = request.getParameter("token");
        
        if (token == null || token.trim().isEmpty()) {
            logger.warn("Intento de confirmación de baja sin token desde IP: {}", request.getRemoteAddr());
            sendJsonResponse(response, HttpServletResponse.SC_BAD_REQUEST, false, "Token no proporcionado.");
            return;
        }

        Connection conn = null;
        try {
            conn = DatabaseUtil.getConnection();
            conn.setAutoCommit(false);

            String usuario = null;
            String sqlRowUuid = null;
            
            // Buscar usuario por token válido y no expirado
            String findUserSql = "SELECT Usuario, SqlRowUUID FROM voluntarios WHERE token_baja = ? AND token_baja_expiry > ?";
            try (PreparedStatement psFind = conn.prepareStatement(findUserSql)) {
                psFind.setString(1, token);
                psFind.setTimestamp(2, Timestamp.from(Instant.now()));
                
                try (ResultSet rs = psFind.executeQuery()) {
                    if (rs.next()) {
                        usuario = rs.getString("Usuario");
                        sqlRowUuid = rs.getString("SqlRowUUID");
                    }
                }
            }

            if (usuario != null) {
                Timestamp bajaTimestamp = Timestamp.from(Instant.now());

                // Actualizar usuario: establecer fecha baja y limpiar tokens
                String updateUserSql = "UPDATE voluntarios SET fecha_baja = ?, token_baja = NULL, token_baja_expiry = NULL, notificar = 'S' WHERE Usuario = ?";
                try (PreparedStatement psUpdate = conn.prepareStatement(updateUserSql)) {
                    psUpdate.setTimestamp(1, bajaTimestamp);
                    psUpdate.setString(2, usuario);
                    psUpdate.executeUpdate();
                }

                LogUtil.logOperation(conn, "BAJA-CONFIRM", usuario, "El usuario ha confirmado su baja en la base de datos.");

                // Intentar replicar a SharePoint (no bloqueante)
                if (sqlRowUuid != null) {
                    try {
                        Map<String, Object> spData = new HashMap<>();
                        String isoDate = bajaTimestamp.toInstant().atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                        spData.put("field_21", isoDate); // Campo fecha de baja en SharePoint

                        // Nota: "voluntarios" o "Voluntarios" debe coincidir con el nombre de la lista en SP
                        SharepointReplicationUtil.replicate(conn, SharePointUtil.SITE_ID, "Voluntarios", spData, SharepointReplicationUtil.Operation.UPDATE, sqlRowUuid);

                        LogUtil.logOperation(conn, "REPLICATE_SUCCESS", usuario, "Baja replicada a SharePoint (marcado como inactivo).");
                    } catch (Exception e) {
                        // Logueamos el error pero permitimos que la transacción de BD continúe
                        logger.error("Fallo al replicar la baja a SharePoint para el UUID: {}", sqlRowUuid, e);
                        LogUtil.logOperation(conn, "REPLICATE_ERROR", usuario, "Fallo al replicar la baja a SharePoint: " + e.getMessage());
                    }
                } else {
                     logger.warn("Usuario {} dado de baja, pero no tenía SqlRowUUID. No se pudo replicar a SharePoint.", usuario);
                     LogUtil.logOperation(conn, "REPLICATE_WARNING", usuario, "No se encontró SqlRowUUID para replicar la baja a SharePoint.");
                }

                conn.commit();
                logger.info("Baja confirmada exitosamente para el usuario: {}", usuario);
                sendJsonResponse(response, HttpServletResponse.SC_OK, true, "Tu cuenta ha sido dada de baja correctamente.");

            } else {
                // Token no encontrado o expirado
                conn.rollback();
                logger.warn("Intento de baja con token inválido o expirado: {}", token);
                sendJsonResponse(response, HttpServletResponse.SC_BAD_REQUEST, false, "El enlace de confirmación no es válido o ha caducado. Por favor, solicita la baja de nuevo.");
            }

        } catch (SQLException e) {
            logger.error("Error SQL procesando baja con token: {}", token, e);
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) { logger.warn("Error en rollback", ex); }
            sendJsonResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, false, "Error de base de datos. Inténtalo más tarde.");
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException e) { logger.warn("Error cerrando conexión", e); }
        }
    }
    
    private void sendJsonResponse(HttpServletResponse response, int status, boolean success, String message) throws IOException {
        response.setStatus(status);
        Map<String, Object> json = new HashMap<>();
        json.put("success", success);
        json.put("message", message);
        mapper.writeValue(response.getWriter(), json);
    }
}