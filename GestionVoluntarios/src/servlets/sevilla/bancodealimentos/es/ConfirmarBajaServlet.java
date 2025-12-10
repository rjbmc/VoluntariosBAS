package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

import com.google.gson.JsonObject;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import util.sevilla.bancodealimentos.es.DatabaseUtil;
import util.sevilla.bancodealimentos.es.LogUtil;
import util.sevilla.bancodealimentos.es.SharepointReplicationUtil;
import util.sevilla.bancodealimentos.es.SharepointUtil;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@WebServlet("/confirmar-baja")
public class ConfirmarBajaServlet extends HttpServlet {
    private static final long serialVersionUID = 3L; // Versión actualizada
    private static final Logger logger = LogManager.getLogger(ConfirmarBajaServlet.class);

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String token = request.getParameter("token");
        JsonObject jsonResponse = new JsonObject();
        
        if (token == null || token.trim().isEmpty()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            jsonResponse.addProperty("success", false);
            jsonResponse.addProperty("message", "Token no proporcionado.");
            response.getWriter().write(jsonResponse.toString());
            return;
        }

        try (Connection conn = DatabaseUtil.getConnection()) {
            conn.setAutoCommit(false);

            String usuario = null;
            String sqlRowUuid = null;
            
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

                String updateUserSql = "UPDATE voluntarios SET fecha_baja = ?, token_baja = NULL, token_baja_expiry = NULL, notificar = 'S' WHERE Usuario = ?";
                try (PreparedStatement psUpdate = conn.prepareStatement(updateUserSql)) {
                    psUpdate.setTimestamp(1, bajaTimestamp);
                    psUpdate.setString(2, usuario);
                    psUpdate.executeUpdate();
                }

                LogUtil.logOperation(conn, "BAJA-CONFIRM", usuario, "El usuario ha confirmado su baja en la base de datos.");

                if (sqlRowUuid != null) {
                    try {
                        Map<String, Object> spData = new HashMap<>();
                        String isoDate = bajaTimestamp.toInstant().atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                        spData.put("field_21", isoDate); 

                        // ** INICIO DE LA CORRECCIÓN **
                        SharepointReplicationUtil.replicate(conn, SharepointUtil.SITE_ID, "voluntarios", spData, SharepointReplicationUtil.Operation.UPDATE, sqlRowUuid);
                        // ** FIN DE LA CORRECCIÓN **

                        LogUtil.logOperation(conn, "REPLICATE_SUCCESS", usuario, "Baja replicada a SharePoint (marcado como inactivo).");
                    } catch (Exception e) {
                        logger.warn("Fallo al replicar baja a SharePoint para uuid={}: {}", sqlRowUuid, e.getMessage());
                        logger.debug("Traza completa replicación baja", e);
                        LogUtil.logOperation(conn, "REPLICATE_ERROR", usuario, "Fallo al replicar la baja a SharePoint: " + e.getMessage());
                    }
                } else {
                     LogUtil.logOperation(conn, "REPLICATE_WARNING", usuario, "No se encontró SqlRowUUID para replicar la baja a SharePoint.");
                }

                conn.commit();
                jsonResponse.addProperty("success", true);
                jsonResponse.addProperty("message", "Tu cuenta ha sido dada de baja correctamente.");

            } else {
                conn.rollback();
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                jsonResponse.addProperty("success", false);
                jsonResponse.addProperty("message", "El enlace de confirmación no es válido o ha caducado. Por favor, solicita la baja de nuevo.");
            }

        } catch (SQLException e) {
            logger.error("Error de BD al confirmar baja", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            jsonResponse.addProperty("success", false);
            jsonResponse.addProperty("message", "Error de base de datos. Inténtalo más tarde.");
        } 

        response.getWriter().write(jsonResponse.toString());
    }
}