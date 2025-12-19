package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Map;

import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import util.sevilla.bancodealimentos.es.DatabaseUtil;
import util.sevilla.bancodealimentos.es.LogUtil;
import util.sevilla.bancodealimentos.es.SharepointReplicationUtil;
import util.sevilla.bancodealimentos.es.SharePointUtil;

@WebServlet("/solicitar-baja")
public class SolicitarBajaServlet extends HttpServlet {
    private static final long serialVersionUID = 5L;
    
    // 1. Logger SLF4J
    private static final Logger logger = LoggerFactory.getLogger(SolicitarBajaServlet.class);
    
    // 2. Jackson ObjectMapper
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("usuario") == null) {
            logger.warn("Intento de baja sin sesión activa. IP: {}", request.getRemoteAddr());
            sendJsonResponse(response, HttpServletResponse.SC_UNAUTHORIZED,
                    Map.of("message", "No hay una sesión de usuario activa. Por favor, inicia sesión de nuevo."));
            return;
        }
        
        String usuario = (String) session.getAttribute("usuario");
        logger.info("Usuario {} ha iniciado el proceso de baja con contraseña.", usuario);

        try (Connection conn = DatabaseUtil.getConnection()) {
            conn.setAutoCommit(false); // Inicio transacción

            // Leer JSON del cuerpo de la petición
            JsonNode body;
            try {
                body = objectMapper.readTree(request.getReader());
            } catch (JsonProcessingException e) {
                logger.warn("Formato JSON inválido en solicitud de baja de {}", usuario);
                sendJsonResponse(response, HttpServletResponse.SC_BAD_REQUEST, Map.of("message", "La solicitud no tiene el formato esperado."));
                return;
            }

            if (body == null || !body.has("password") || body.get("password").asText().isEmpty()) {
                 sendJsonResponse(response, HttpServletResponse.SC_BAD_REQUEST, Map.of("message", "El campo 'password' es requerido."));
                 return;
            }
            
            String plainPassword = body.get("password").asText();
            String hashedPassword = null;
            String sqlRowUuid = null;

            // Verificar contraseña actual
            String sqlSelect = "SELECT Clave, SqlRowUUID FROM voluntarios WHERE Usuario = ?";
            try (PreparedStatement ps = conn.prepareStatement(sqlSelect)) {
                ps.setString(1, usuario);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        hashedPassword = rs.getString("Clave");
                        sqlRowUuid = rs.getString("SqlRowUUID");
                    }
                }
            }
            
            if (hashedPassword == null || !BCrypt.checkpw(plainPassword, hashedPassword)) {
                logger.warn("Intento de baja fallido para {}: Contraseña incorrecta.", usuario);
                LogUtil.logOperation(conn, "SOLICITUD_BAJA_FAIL", usuario, "Intento de baja con contraseña incorrecta.");
                conn.rollback(); 
                sendJsonResponse(response, HttpServletResponse.SC_FORBIDDEN, Map.of("message", "La contraseña es incorrecta."));
                return;
            }

            // Ejecutar Baja
            Date fechaBajaSql = new Date(System.currentTimeMillis());
            String sqlUpdateBaja = "UPDATE voluntarios SET fecha_baja = ?, notificar = 'S' WHERE Usuario = ?";
            try (PreparedStatement psUpdate = conn.prepareStatement(sqlUpdateBaja)) {
                psUpdate.setDate(1, fechaBajaSql);
                psUpdate.setString(2, usuario);
                psUpdate.executeUpdate();
            }

            // Replicar a SharePoint
            if (sqlRowUuid != null && !sqlRowUuid.isEmpty()) {
                try {
                    Map<String, Object> spData = new HashMap<>();
                    String fechaBajaString = new SimpleDateFormat("yyyy-MM-dd").format(fechaBajaSql);
                    spData.put("field_21", fechaBajaString); // Campo 'FechaBaja' en SP
                    
                    SharepointReplicationUtil.replicate(conn, SharePointUtil.SP_SITE_ID_VOLUNTARIOS, "voluntarios", spData, SharepointReplicationUtil.Operation.UPDATE, sqlRowUuid);
                } catch (Exception e) {
                    logger.error("Error al replicar baja a SharePoint para {}", usuario, e);
                    // No hacemos rollback porque la baja local es prioritaria
                }
            }
            
            LogUtil.logOperation(conn, "BAJA_OK", usuario, "El usuario se ha dado de baja mediante contraseña.");
            conn.commit(); 

            // Cerrar sesión
            session.invalidate();
            logger.info("Baja completada exitosamente para {}", usuario);

            Map<String, Object> responseData = new HashMap<>();
            responseData.put("success", true);
            responseData.put("message", "Tu solicitud de baja ha sido procesada correctamente. Serás redirigido a la página de inicio.");
            responseData.put("redirectUrl", "login.html");
            
            sendJsonResponse(response, HttpServletResponse.SC_OK, responseData);

        } catch (SQLException e) {
            logger.error("Error SQL procesando baja de {}", usuario, e);
            sendJsonResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, Map.of("message", "Error de base de datos al procesar la solicitud."));
        } catch (Exception e) {
            logger.error("Error inesperado procesando baja de {}", usuario, e);
            sendJsonResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, Map.of("message", "Ha ocurrido un error inesperado."));
        }
    }

    private void sendJsonResponse(HttpServletResponse response, int statusCode, Map<String, Object> data) throws IOException {
        if (!response.isCommitted()) {
            response.setStatus(statusCode);
            response.setContentType("application/json");
            response.setCharacterEncoding("UTF-8");
            objectMapper.writeValue(response.getWriter(), data);
        }
    }
}