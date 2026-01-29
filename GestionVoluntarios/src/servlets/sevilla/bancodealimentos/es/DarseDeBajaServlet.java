package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
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
import jakarta.mail.MessagingException;
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
import util.sevilla.bancodealimentos.es.LogUtil;

@WebServlet("/darse-de-baja")
public class DarseDeBajaServlet extends HttpServlet {
    private static final long serialVersionUID = 3L; // Versión incrementada
    private static final Logger logger = LoggerFactory.getLogger(DarseDeBajaServlet.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("usuario") == null || session.getAttribute("email") == null) {
            sendJsonResponse(response, HttpServletResponse.SC_UNAUTHORIZED, false, "Acceso no autorizado.");
            return;
        }

        String usuario = (String) session.getAttribute("usuario");
        String email = (String) session.getAttribute("email");
        String context = "Usuario: " + usuario;

        Connection conn = null;
        try {
            conn = DatabaseUtil.getConnection();
            conn.setAutoCommit(false);

            // 1. Generar y guardar el token de baja en la BD
            String tokenBaja = generateAndStoreBajaToken(conn, usuario);

            // 2. Enviar el email de confirmación
            sendConfirmationEmail(request, email, tokenBaja, usuario);

            // 3. Si todo tiene éxito, confirmar la transacción
            conn.commit();
            LogUtil.logOperation(conn, "UNSUBSCRIBE_REQUEST", usuario, "Solicitud de baja iniciada y correo enviado.");
            sendJsonResponse(response, HttpServletResponse.SC_OK, true, "Se ha enviado un correo de confirmación. Por favor, sigue las instrucciones para completar la baja.");

        } catch (Exception e) {
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) { LogUtil.logException(logger, ex, "CRITICAL: Rollback fallido al solicitar baja", context); }
            LogUtil.logException(logger, e, "Error en el proceso de solicitud de baja", context);
            sendJsonResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, false, "Error interno al procesar la solicitud. El problema ha sido registrado.");
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException e) { LogUtil.logException(logger, e, "Error cerrando conexión en DarseDeBajaServlet", context); }
        }
    }
    
    private String generateAndStoreBajaToken(Connection conn, String usuario) throws SQLException {
        String tokenBaja = UUID.randomUUID().toString();
        Timestamp expiryDate = Timestamp.from(Instant.now().plus(1, ChronoUnit.HOURS));

        String sql = "UPDATE voluntarios SET token_baja = ?, token_baja_expiry = ?, notificar = 'S' WHERE Usuario = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tokenBaja);
            ps.setTimestamp(2, expiryDate);
            ps.setString(3, usuario);
            int rowsAffected = ps.executeUpdate();
            if (rowsAffected == 0) {
                throw new SQLException("No se encontró el usuario '" + usuario + "' para actualizar el token de baja.");
            }
        }
        return tokenBaja;
    }

    private void sendConfirmationEmail(HttpServletRequest request, String emailDestino, String token, String nombreUsuario) throws MessagingException, UnsupportedEncodingException {
        String baseUrl = request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort() + request.getContextPath();
        String confirmationLink = baseUrl + "/confirmar-baja.html?token=" + token;

        Properties prop = new Properties();
        prop.put("mail.smtp.host", Config.SMTP_HOST); prop.put("mail.smtp.port", Config.SMTP_PORT);
        prop.put("mail.smtp.auth", "true"); prop.put("mail.smtp.starttls.enable", "true");

        Session mailSession = Session.getInstance(prop, new Authenticator() {
            protected PasswordAuthentication getPasswordAuthentication() { return new PasswordAuthentication(Config.SMTP_USER, Config.SMTP_PASSWORD); }
        });

        Message message = new MimeMessage(mailSession);
        message.setFrom(new InternetAddress(Config.SMTP_USER, "Banco de Alimentos de Sevilla"));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(emailDestino));
        message.setSubject("Confirma tu solicitud de baja de voluntario/a");

        String emailBody = "<h1>Confirmación de Baja</h1>"
                         + "<p>Hola " + nombreUsuario + ",</p>"
                         + "<p>Hemos recibido una solicitud para dar de baja tu cuenta en la aplicación de Voluntarios del Banco de Alimentos de Sevilla.</p>"
                         + "<p>Si has sido tú, por favor, haz clic en el siguiente enlace para confirmar la operación. Este enlace caducará en 1 hora.</p>"
                         + "<p><a href='" + confirmationLink + "'>SÍ, QUIERO DARME DE BAJA</a></p>"
                         + "<p>Si no has solicitado esto, puedes ignorar este correo de forma segura. Tu cuenta no será eliminada.</p>"
                         + "<p>Gracias.</p>";

        message.setContent(emailBody, "text/html; charset=utf-8");
        Transport.send(message);
    }
    
    private void sendJsonResponse(HttpServletResponse response, int status, boolean success, String message) throws IOException {
        if (!response.isCommitted()) {
            response.setStatus(status);
            Map<String, Object> jsonResponse = new HashMap<>();
            jsonResponse.put("success", success);
            jsonResponse.put("message", message);
            mapper.writeValue(response.getWriter(), jsonResponse);
        }
    }
}