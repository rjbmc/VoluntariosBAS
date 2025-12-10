// Paquete: servlets.sevilla.bancodealimentos.es
package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Properties;
import java.util.UUID;

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

/**
 * Servlet que gestiona la solicitud de recuperación de contraseña.
 */
@WebServlet("/recuperar-clave")
public class RecuperarClaveServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String email = request.getParameter("email");

        try (Connection conn = DatabaseUtil.getConnection()) {
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

                String sqlUpdate = "UPDATE voluntarios SET reset_token = ?, reset_token_expiry = ? WHERE Usuario = ?";
                try (PreparedStatement stmtUpdate = conn.prepareStatement(sqlUpdate)) {
                    stmtUpdate.setString(1, token);
                    stmtUpdate.setTimestamp(2, expiryTimestamp);
                    stmtUpdate.setString(3, usuario);
                    stmtUpdate.executeUpdate();
                }

                sendPasswordResetEmail(email, usuario, token, request);
                
                // --- CAMBIO: Se elimina el último Parámetro de la llamada al log ---
                LogUtil.logOperation(conn, "RECUPERACION_SOL", usuario, "Solicitud de recuperación de clave para " + email);
            }
            
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().print("{\"success\": true}");


        } catch (SQLException e) {
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().print("{\"success\": true}");
        }
    }

    private void sendPasswordResetEmail(String emailDestino, String usuario, String token, HttpServletRequest request) {
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

        try {
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
                             + "El equipo del Banco de Alimentos de Sevilla."
                             + "<br><br><strong>La dirección de correo desde donde se envía este mensaje es de sólo envío. Por favor, no la uses para responder.</strong><br>";
            message.setContent(emailBody, "text/html; charset=utf-8");
            Transport.send(message);
        } catch (MessagingException e) {
            e.printStackTrace();
        }
    }
}
