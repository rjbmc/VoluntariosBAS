package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
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
import util.sevilla.bancodealimentos.es.PasswordUtils;

@WebServlet("/restablecer-clave")
public class RestablecerClaveServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final Logger logger = LoggerFactory.getLogger(RestablecerClaveServlet.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String token = request.getParameter("token");
        String nuevaClave = request.getParameter("nuevaClave");

        if (token == null || token.trim().isEmpty() || nuevaClave == null || nuevaClave.trim().isEmpty()) {
            sendJsonResponse(response, HttpServletResponse.SC_BAD_REQUEST, false, "Token o contraseña no proporcionados.");
            return;
        }

        Connection conn = null;
        String usuario = "Desconocido (Token: " + token + ")";

        try {
            conn = DatabaseUtil.getConnection();
            Timestamp expiryTime = null;

            String sqlFindUser = "SELECT Usuario, fecha_expiracion_token FROM voluntarios WHERE token_recuperacion_clave = ?";
            try (PreparedStatement stmtFind = conn.prepareStatement(sqlFindUser)) {
                stmtFind.setString(1, token);
                try (ResultSet rs = stmtFind.executeQuery()) {
                    if (rs.next()) {
                        usuario = rs.getString("Usuario");
                        expiryTime = rs.getTimestamp("fecha_expiracion_token");
                    }
                }
            }

            if (usuario == null || expiryTime == null || expiryTime.before(new Timestamp(System.currentTimeMillis()))) {
                sendJsonResponse(response, HttpServletResponse.SC_BAD_REQUEST, false, "El enlace de recuperación no es válido o ha caducado. Por favor, solicita uno nuevo.");
                return;
            }

            String nuevaClaveHasheada = PasswordUtils.hashPassword(nuevaClave);
            String sqlUpdate = "UPDATE voluntarios SET Clave = ?, token_recuperacion_clave = NULL, fecha_expiracion_token = NULL, notificar = 'S' WHERE Usuario = ?";

            try (PreparedStatement stmtUpdate = conn.prepareStatement(sqlUpdate)) {
                stmtUpdate.setString(1, nuevaClaveHasheada);
                stmtUpdate.setString(2, usuario);
                int rows = stmtUpdate.executeUpdate();
                
                if (rows > 0) {
                    LogUtil.logOperation(conn, "RECUPERACION_OK", usuario, "Contraseña restablecida correctamente via token.");
                    sendJsonResponse(response, HttpServletResponse.SC_OK, true, "¡Contraseña actualizada con éxito! Ya puedes iniciar sesión.");
                } else {
                    // Este caso es muy improbable si el token era válido, pero se registra por si acaso.
                    LogUtil.logException(logger, new Exception("No se pudo actualizar la contraseña a pesar de tener un token válido."), "Error al actualizar contraseña con token válido", usuario);
                    sendJsonResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, false, "Error al actualizar la contraseña. El error ha sido registrado.");
                }
            }

        } catch (SQLException e) {
            LogUtil.logException(logger, e, "Error SQL al restablecer la contraseña", usuario);
            sendJsonResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, false, "Error de base de datos. El problema ha sido registrado.");
        } finally {
            if (conn != null) {
                try { conn.close(); } catch (SQLException e) {
                    LogUtil.logException(logger, e, "Error al cerrar conexión en RestablecerClaveServlet");
                }
            }
        }
    }

    private void sendJsonResponse(HttpServletResponse response, int statusCode, boolean success, String message) throws IOException {
        if (!response.isCommitted()) {
            response.setStatus(statusCode);
            Map<String, Object> jsonResponse = new HashMap<>();
            jsonResponse.put("success", success);
            jsonResponse.put("message", message);
            mapper.writeValue(response.getWriter(), jsonResponse);
        }
    }
}