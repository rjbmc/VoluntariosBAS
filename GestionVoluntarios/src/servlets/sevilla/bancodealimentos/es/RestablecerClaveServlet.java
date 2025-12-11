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

/**
 * Servlet que finaliza el proceso de restablecimiento de contraseña.
 * Verifica el token y actualiza la contraseña del usuario.
 */
@WebServlet("/restablecer-clave")
public class RestablecerClaveServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    
    // 1. Logger SLF4J
    private static final Logger logger = LoggerFactory.getLogger(RestablecerClaveServlet.class);
    
    // 2. Jackson ObjectMapper
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String token = request.getParameter("token");
        String nuevaClave = request.getParameter("nuevaClave");

        if (token == null || token.trim().isEmpty() || nuevaClave == null || nuevaClave.trim().isEmpty()) {
            logger.warn("Intento de restablecer clave sin token o clave. IP: {}", request.getRemoteAddr());
            sendJsonResponse(response, HttpServletResponse.SC_BAD_REQUEST, false, "Token o contraseña no proporcionados.");
            return;
        }

        try (Connection conn = DatabaseUtil.getConnection()) {
            String usuario = null;
            Timestamp expiryTime = null;

            // 1. Buscar el usuario por el token
            String sqlFindUser = "SELECT Usuario, reset_token_expiry FROM voluntarios WHERE reset_token = ?";
            try (PreparedStatement stmtFind = conn.prepareStatement(sqlFindUser)) {
                stmtFind.setString(1, token);
                try (ResultSet rs = stmtFind.executeQuery()) {
                    if (rs.next()) {
                        usuario = rs.getString("Usuario");
                        expiryTime = rs.getTimestamp("reset_token_expiry");
                    }
                }
            }

            // 2. Validar el token y su caducidad
            if (usuario == null || expiryTime == null || expiryTime.before(new Timestamp(System.currentTimeMillis()))) {
                logger.warn("Intento de restablecimiento con token inválido o expirado: {}", token);
                sendJsonResponse(response, HttpServletResponse.SC_BAD_REQUEST, false, "El enlace de recuperación no es válido o ha caducado. Por favor, solicita uno nuevo.");
                return;
            }

            // 3. Si todo es correcto, actualizar la contraseña
            String nuevaClaveHasheada = PasswordUtils.hashPassword(nuevaClave);
            String sqlUpdate = "UPDATE voluntarios SET Clave = ?, reset_token = NULL, reset_token_expiry = NULL, notificar = 'S' WHERE Usuario = ?";

            try (PreparedStatement stmtUpdate = conn.prepareStatement(sqlUpdate)) {
                stmtUpdate.setString(1, nuevaClaveHasheada);
                stmtUpdate.setString(2, usuario);
                int rows = stmtUpdate.executeUpdate();
                
                if (rows > 0) {
                    LogUtil.logOperation(conn, "RECUPERACION_OK", usuario, "Contraseña restablecida correctamente via token.");
                    logger.info("Contraseña restablecida exitosamente para el usuario: {}", usuario);
                    sendJsonResponse(response, HttpServletResponse.SC_OK, true, "¡Contraseña actualizada con éxito! Ya puedes iniciar sesión.");
                } else {
                    logger.error("No se pudo actualizar la contraseña para el usuario {} a pesar de tener token válido.", usuario);
                    sendJsonResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, false, "Error al actualizar la contraseña.");
                }
            }

        } catch (SQLException e) {
            logger.error("Error SQL al restablecer la contraseña", e);
            sendJsonResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, false, "Error de base de datos al actualizar la contraseña.");
        }
    }

    private void sendJsonResponse(HttpServletResponse response, int statusCode, boolean success, String message) throws IOException {
        response.setStatus(statusCode);
        Map<String, Object> jsonResponse = new HashMap<>();
        jsonResponse.put("success", success);
        jsonResponse.put("message", message);
        mapper.writeValue(response.getWriter(), jsonResponse);
    }
}