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
import com.microsoft.graph.models.FieldValueSet;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import util.sevilla.bancodealimentos.es.DatabaseUtil;
import util.sevilla.bancodealimentos.es.LogUtil;
import util.sevilla.bancodealimentos.es.SharePointUtil;

@WebServlet("/confirmar-baja")
public class ConfirmarBajaServlet extends HttpServlet {
    private static final long serialVersionUID = 5L; // Versión incrementada
    private static final Logger logger = LoggerFactory.getLogger(ConfirmarBajaServlet.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String token = request.getParameter("token");
        if (token == null || token.trim().isEmpty()) {
            sendJsonResponse(response, HttpServletResponse.SC_BAD_REQUEST, false, "Token no proporcionado.");
            return;
        }

        String context = "Token: " + token.substring(0, Math.min(token.length(), 8)) + "...";
        Connection conn = null;

        try {
            conn = DatabaseUtil.getConnection();
            conn.setAutoCommit(false);

            // 1. Encontrar usuario por token válido
            UserInfo userInfo = findUserByToken(conn, token);

            if (userInfo == null) {
                conn.rollback();
                sendJsonResponse(response, HttpServletResponse.SC_BAD_REQUEST, false, "El enlace de confirmación no es válido o ha caducado. Por favor, solicita la baja de nuevo.");
                return;
            }
            
            context = String.format("Usuario: %s", userInfo.username);
            Timestamp bajaTimestamp = Timestamp.from(Instant.now());

            // 2. Marcar como baja en la BD
            deactivateUserInDb(conn, userInfo.username, bajaTimestamp);

            // 3. Sincronizar baja con SharePoint
            deactivateUserInSharePoint(conn, userInfo.sqlRowUuid, bajaTimestamp, userInfo.username);

            // 4. Si todo tiene éxito, confirmar la transacción
            conn.commit();
            LogUtil.logOperation(conn, "UNSUBSCRIBE_SUCCESS", userInfo.username, "Baja confirmada y sincronizada.");
            sendJsonResponse(response, HttpServletResponse.SC_OK, true, "Tu cuenta ha sido dada de baja correctamente.");

        } catch (Exception e) {
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) { LogUtil.logException(logger, ex, "CRITICAL: Rollback fallido en confirmación de baja", context); }
            LogUtil.logException(logger, e, "Error en el proceso de confirmación de baja", context);
            sendJsonResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, false, "Error interno al procesar la baja. El problema ha sido registrado.");
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException e) { LogUtil.logException(logger, e, "Error cerrando conexión en ConfirmarBajaServlet", context); }
        }
    }

    private UserInfo findUserByToken(Connection conn, String token) throws SQLException {
        String sql = "SELECT Usuario, SqlRowUUID FROM voluntarios WHERE token_baja = ? AND token_baja_expiry > ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, token);
            ps.setTimestamp(2, Timestamp.from(Instant.now()));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new UserInfo(rs.getString("Usuario"), rs.getString("SqlRowUUID"));
                }
                return null;
            }
        }
    }

    private void deactivateUserInDb(Connection conn, String username, Timestamp bajaTimestamp) throws SQLException {
        String sql = "UPDATE voluntarios SET fecha_baja = ?, token_baja = NULL, token_baja_expiry = NULL, notificar = 'S' WHERE Usuario = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, bajaTimestamp);
            ps.setString(2, username);
            if (ps.executeUpdate() == 0) {
                throw new SQLException("No se actualizó ninguna fila para el usuario '" + username + "'. El token podría haber sido usado en una transacción paralela.");
            }
        }
    }

    private void deactivateUserInSharePoint(Connection conn, String sqlRowUuid, Timestamp bajaTimestamp, String username) throws Exception {
        if (sqlRowUuid == null || sqlRowUuid.isEmpty()) {
            throw new Exception("No se puede sincronizar la baja con SharePoint: el SqlRowUUID del usuario '" + username + "' es nulo. La transacción se revertirá.");
        }

        String listId = SharePointUtil.getListId(SharePointUtil.SP_SITE_ID_VOLUNTARIOS, "Voluntarios");
        if (listId == null) {
            throw new Exception("No se pudo encontrar la lista 'Voluntarios' en SharePoint. La transacción se revertirá.");
        }

        String itemId = SharePointUtil.findItemIdByFieldValue(conn, SharePointUtil.SP_SITE_ID_VOLUNTARIOS, listId, "SqlRowUUID", sqlRowUuid);
        if (itemId == null) {
            logger.warn("El voluntario '{}' (UUID: {}) no fue encontrado en SharePoint durante la confirmación de su baja. La baja en la BD local se revertirá.", username, sqlRowUuid);
            throw new Exception("El voluntario no fue encontrado en SharePoint. La baja ha sido cancelada para mantener la consistencia.");
        }

        FieldValueSet fieldsToUpdate = new FieldValueSet();
        String isoDate = bajaTimestamp.toInstant().atZone(ZoneId.of("Europe/Madrid")).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        fieldsToUpdate.getAdditionalData().put("FechaBaja", isoDate);

        SharePointUtil.updateListItem(SharePointUtil.SP_SITE_ID_VOLUNTARIOS, listId, itemId, fieldsToUpdate);
    }
    
    private static class UserInfo {
        final String username, sqlRowUuid;
        UserInfo(String u, String id) { this.username = u; this.sqlRowUuid = id; }
    }

    private void sendJsonResponse(HttpServletResponse response, int status, boolean success, String message) throws IOException {
        if (!response.isCommitted()) {
            response.setStatus(status);
            Map<String, Object> json = new HashMap<>();
            json.put("success", success);
            json.put("message", message);
            mapper.writeValue(response.getWriter(), json);
        }
    }
}