package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

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

import util.sevilla.bancodealimentos.es.Config;
import util.sevilla.bancodealimentos.es.DatabaseUtil;
import util.sevilla.bancodealimentos.es.LogUtil;

@WebServlet("/recuperar-clave")
public class RecuperarClaveServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final Logger logger = LoggerFactory.getLogger(RecuperarClaveServlet.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        String email = request.getParameter("email");
        Map<String, Object> jsonResponse = new HashMap<>();
        Connection conn = null;

        try {
            conn = DatabaseUtil.getConnection();
            String sqlSelect = "SELECT Usuario FROM voluntarios WHERE Email = ?";
            String usuario = null;

            try (PreparedStatement stmtSelect = conn.prepareStatement(sqlSelect)) {
                stmtSelect.setString(1, email);
                try (ResultSet rs = stmtSelect.executeQuery()) {
                    if (rs.next()) {
                        usuario = rs.getString("Usuario");
                    }
                }
            }

            if (usuario != null) {
                String token = UUID.randomUUID().toString();
                long expiryTime = System.currentTimeMillis() + 3600 * 1000; // 1 hora de validez
                Timestamp expiryTimestamp = new Timestamp(expiryTime);

                String sqlUpdate = "UPDATE voluntarios SET token_recuperacion_clave = ?, fecha_expiracion_token = ? WHERE Usuario = ?";
                try (PreparedStatement stmtUpdate = conn.prepareStatement(sqlUpdate)) {
                    stmtUpdate.setString(1, token);
                    stmtUpdate.setTimestamp(2, expiryTimestamp);
                    stmtUpdate.setString(3, usuario);
                    stmtUpdate.executeUpdate();
                }

                try {
                    sendPasswordResetEmail(email, usuario, token, request);
                    LogUtil.logOperation(conn, "RECUPERACION_SOL", usuario, "Solicitud de recuperación de clave para " + email);
                } catch (MessagingException e) {
                    LogUtil.logException(logger, e, "Error al enviar email de recuperación de clave", usuario);
                }
            } 
            
            response.setStatus(HttpServletResponse.SC_OK);
            jsonResponse.put("success", true);
            jsonResponse.put("message", "Si el correo electrónico existe en nuestra base de datos, recibirás las instrucciones para restablecer tu contraseña.");

        } catch (SQLException e) {
            LogUtil.logException(logger, e, "Error de BD en recuperación de clave", "Email: " + email);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            jsonResponse.put("success", false);
            jsonResponse.put("message", "Error interno del servidor. El problema ha sido registrado.");
        } finally {
            if (conn != null) {
                try { conn.close(); } catch (SQLException e) {
                    LogUtil.logException(logger, e, "Error al cerrar conexión en RecuperarClaveServlet");
                }
            }
        }
        
        mapper.writeValue(response.getWriter(), jsonResponse);
    }

    private void sendPasswordResetEmail(String emailDestino, String usuario, String token, HttpServletRequest request) throws MessagingException {
        final String username = Config.SMTP_USER;
        final String password = Config.SMTP_PASSWORD;

        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.host", Config.SMTP_HOST);
        props.put("mail.smtp.port", Config.SMTP_PORT);

        Session session = Session.getInstance(props, new jakarta.mail.Authenticator() {
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(username, password);
            }
        });

        Message message = new MimeMessage(session);
        message.setFrom(new InternetAddress(username));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(emailDestino));
        message.setSubject("Restablecimiento de contraseña - Banco de Alimentos de Sevilla");

        String urlBase = request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort() + request.getContextPath();
        String resetLink = urlBase + "/restablecer-clave.html?token=" + token;

        String emailBody = "Hola,<br><br>"
                         + "El usuario " + usuario + " ha solicitado restablecer tu contraseña para la aplicación de voluntarios del Banco de Alimentos de Sevilla.<br><br>"
                         + "Por favor, haz clic en el siguiente enlace para crear una nueva contraseña:<br>"
                         + "<a href=\"" + resetLink + "\">Restablecer mi contraseña</a><br><br>"
                         + "Si no has solicitado esto, puedes ignorar este correo.<br><br>"
                         + "Gracias,<br>"
                         + "El equipo del Banco de Alimentos de Sevilla.";
        
        message.setContent(emailBody, "text/html; charset=utf-8");
        Transport.send(message);
    }
}