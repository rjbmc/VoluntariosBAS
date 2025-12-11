package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import util.sevilla.bancodealimentos.es.Config;
import util.sevilla.bancodealimentos.es.DatabaseUtil;

@WebServlet("/darse-de-baja")
public class DarseDeBajaServlet extends HttpServlet {
    private static final long serialVersionUID = 2L;
    
    // 1. Logger SLF4J
    private static final Logger logger = LoggerFactory.getLogger(DarseDeBajaServlet.class);
    
    // 2. Jackson ObjectMapper
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        HttpSession session = request.getSession(false);

        if (session == null || session.getAttribute("usuario") == null) {
            logger.warn("Intento de baja sin sesión activa. IP: {}", request.getRemoteAddr());
            sendJsonResponse(response, HttpServletResponse.SC_FORBIDDEN, false, "No tienes permiso para realizar esta acción.");
            return;
        }

        String usuario = (String) session.getAttribute("usuario");
        String email = (String) session.getAttribute("email");

        if (email == null || email.isEmpty()) {
             logger.error("Usuario {} intentó darse de baja pero no tiene email en sesión.", usuario);
             sendJsonResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, false, "No se pudo encontrar el email del usuario en la sesión.");
             return;
        }

        logger.info("Iniciando proceso de solicitud de baja para el usuario: {}", usuario);

        try {
            String tokenBaja = UUID.randomUUID().toString();
            // Token válido por 1 hora
            Timestamp expiryDate = Timestamp.from(Instant.now().plus(1, ChronoUnit.HOURS));

            try (Connection conn = DatabaseUtil.getConnection()) {
                String sql = "UPDATE voluntarios SET token_baja = ?, token_baja_expiry = ? WHERE Usuario = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, tokenBaja);
                    ps.setTimestamp(2, expiryDate);
                    ps.setString(3, usuario);
                    int rows = ps.executeUpdate();
                    if (rows > 0) {
                        logger.debug("Token de baja generado y guardado para usuario {}", usuario);
                    } else {
                        logger.warn("No se encontró el usuario {} en la base de datos para actualizar el token de baja.", usuario);
                    }
                }
            } catch (SQLException e) {
                logger.error("Error de base de datos al guardar token de baja para {}", usuario, e);
                throw new ServletException("Error de base de datos al guardar el token de baja", e);
            }

            sendConfirmationEmail(email, tokenBaja, request);
            
            logger.info("Correo de confirmación de baja enviado a {}", email);

            sendJsonResponse(response, HttpServletResponse.SC_OK, true, "Se ha enviado un correo de confirmación a tu dirección. Por favor, sigue las instrucciones para completar la baja.");

        } catch (Exception e) {
            logger.error("Error interno al procesar la solicitud de baja de {}", usuario, e);
            sendJsonResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, false, "Error interno del servidor. Inténtalo más tarde.");
        }
    }
    
    private void sendConfirmationEmail(String emailDestino, String token, HttpServletRequest request) throws Exception {
        String baseUrl = request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort() + request.getContextPath();
        String confirmationLink = baseUrl + "/confirmar-baja.html?token=" + token;

        final String username = Config.SMTP_USER;
        final String password = Config.SMTP_PASSWORD;

        Properties prop = new Properties();
        prop.put("mail.smtp.host", Config.SMTP_HOST);
        prop.put("mail.smtp.port", Config.SMTP_PORT);
        prop.put("mail.smtp.auth", "true");
        prop.put("mail.smtp.starttls.enable", "true"); 

        Session mailSession = Session.getInstance(prop, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(username, password);
            }
        });

        try {
            Message message = new MimeMessage(mailSession);
            message.setFrom(new InternetAddress(username));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(emailDestino));
            message.setSubject("Confirma tu solicitud de baja - VoluntariosBAS");

            String emailBody = "<h1>Confirmación de Baja</h1>"
                             + "<p>Hola,</p>"
                             + "<p>Hemos recibido una solicitud para dar de baja tu cuenta en la aplicación de Voluntarios del Banco de Alimentos de Sevilla.</p>"
                             + "<p>Si has sido tú, por favor, haz clic en el siguiente enlace para confirmar la operación. Este enlace caducará en 1 hora.</p>"
                             + "<a href='" + confirmationLink + "'>Confirmar mi baja</a>"
                             + "<p>Si no has solicitado esto, puedes ignorar este correo de forma segura.</p>"
                             + "<p>Gracias.</p>"
                             + "<br><br><strong>La dirección de correo desde donde se envía este mensaje es de sólo envío. Por favor, no la uses para responder.</strong><br>";

            message.setContent(emailBody, "text/html; charset=utf-8");

            Transport.send(message); 

        } catch (Exception e) {
            // Propagamos la excepción para que sea logueada correctamente en el doPost
            throw e;
        }
    }
    
    private void sendJsonResponse(HttpServletResponse response, int status, boolean success, String message) throws IOException {
        response.setStatus(status);
        Map<String, Object> jsonResponse = new HashMap<>();
        jsonResponse.put("success", success);
        jsonResponse.put("message", message);
        mapper.writeValue(response.getWriter(), jsonResponse);
    }
}