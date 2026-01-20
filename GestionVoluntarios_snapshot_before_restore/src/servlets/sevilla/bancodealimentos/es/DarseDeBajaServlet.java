package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Properties;
import java.util.UUID;

import com.google.gson.JsonObject;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import util.sevilla.bancodealimentos.es.Config;
import util.sevilla.bancodealimentos.es.DatabaseUtil;
import util.sevilla.bancodealimentos.es.LogUtil;

// JavaMail API
// Nota: NecesitarÃ¡s aÃ±adir los jars de javax.mail y javax.activation a tu WEB-INF/lib
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;


@WebServlet("/darse-de-baja")
public class DarseDeBajaServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        HttpSession session = request.getSession(false);
        JsonObject jsonResponse = new JsonObject();

        // 1. Verificar que hay una sesiÃ³n activa
        if (session == null || session.getAttribute("usuario") == null) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            jsonResponse.addProperty("success", false);
            jsonResponse.addProperty("message", "No tienes permiso para realizar esta acciÃ³n.");
            response.getWriter().write(jsonResponse.toString());
            return;
        }

        String usuario = (String) session.getAttribute("usuario");
        String email = (String) session.getAttribute("email"); // Asumimos que el email se guarda en sesiÃ³n durante el login

        if (email == null || email.isEmpty()) {
             // Si el email no estÃ¡ en sesiÃ³n, habrÃ­a que hacer una consulta extra para obtenerlo
             // Por simplicidad, asumimos que sÃ­ estÃ¡. Si no, habrÃ­a que aÃ±adir esa lÃ³gica aquÃ­.
             response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
             jsonResponse.addProperty("success", false);
             jsonResponse.addProperty("message", "No se pudo encontrar el email del usuario en la sesiÃ³n.");
             response.getWriter().write(jsonResponse.toString());
             return;
        }

        try {
            // 2. Generar un token de baja seguro y su fecha de caducidad (ej. 1 hora)
            String tokenBaja = UUID.randomUUID().toString();
            Timestamp expiryDate = Timestamp.from(Instant.now().plus(1, ChronoUnit.HOURS));

            // 3. Guardar el token y la fecha en la base de datos
            try (Connection conn = DatabaseUtil.getConnection()) {
                String sql = "UPDATE voluntarios SET token_baja = ?, token_baja_expiry = ? WHERE Usuario = ?";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, tokenBaja);
                    ps.setTimestamp(2, expiryDate);
                    ps.setString(3, usuario);
                    ps.executeUpdate();
                }
            } catch (SQLException e) {
                throw new ServletException("Error de base de datos al guardar el token de baja", e);
            }

            // 4. Enviar el correo de confirmaciÃ³n (simulado)
            sendConfirmationEmail(email, tokenBaja, request);

            jsonResponse.addProperty("success", true);
            jsonResponse.addProperty("message", "Se ha enviado un correo de confirmaciÃ³n a tu direcciÃ³n. Por favor, sigue las instrucciones para completar la baja.");
            response.getWriter().write(jsonResponse.toString());

        } catch (Exception e) {
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            jsonResponse.addProperty("success", false);
            jsonResponse.addProperty("message", "Error interno del servidor. IntÃ©ntalo mÃ¡s tarde.");
            response.getWriter().write(jsonResponse.toString());
            e.printStackTrace();
        }
    }
    
    private void sendConfirmationEmail(String emailDestino, String token, HttpServletRequest request) throws Exception {
        // Construir el enlace de confirmaciÃ³n
        String baseUrl = request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort() + request.getContextPath();
        String confirmationLink = baseUrl + "/confirmar-baja.html?token=" + token;

        // --- SIMULACIÃ“N DE ENVÃO DE CORREO ---
        // En un entorno real, el siguiente bloque se descomentarÃ­a y configurarÃ­a.
//        System.out.println("--- SIMULACIÃ“N DE ENVÃO DE CORREO DE CONFIRMACIÃ“N DE BAJA ---");
//        System.out.println("Para: " + emailDestino);
//        System.out.println("Asunto: Confirma tu solicitud de baja");
//        System.out.println("Cuerpo: Para confirmar que quieres dar de baja tu cuenta, por favor, haz clic en el siguiente enlace:");
//        System.out.println(confirmationLink);
//        System.out.println("----------------------------------------------------------");


//         --- CÃ“DIGO REAL PARA ENVIAR CORREO (necesita configuraciÃ³n en Config.java) --- 
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

            String emailBody = "<h1>ConfirmaciÃ³n de Baja</h1>"
                             + "<p>Hola,</p>"
                             + "<p>Hemos recibido una solicitud para dar de baja tu cuenta en la aplicaciÃ³n de Voluntarios del Banco de Alimentos de Sevilla.</p>"
                             + "<p>Si has sido tÃº, por favor, haz clic en el siguiente enlace para confirmar la operaciÃ³n. Este enlace caducarÃ¡ en 1 hora.</p>"
                             + "<a href='" + confirmationLink + "'>Confirmar mi baja</a>"
                             + "<p>Si no has solicitado esto, puedes ignorar este correo de forma segura.</p>"
                             + "<p>Gracias.</p>"
                             + "<br><br><strong>La direcciÃ³n de correo desde donde se envÃ­a este mensaje es de sÃ³lo envÃ­o. Por favor, no la uses para responder.</strong><br>";

            message.setContent(emailBody, "text/html; charset=utf-8");

            Transport.send(message); 
//            System.out.println("Correo de confirmaciÃ³n de baja enviado (simulado).");

        } catch (Exception e) {
            e.printStackTrace();
            throw new ServletException("Error al enviar el correo de confirmaciÃ³n de baja.", e);
        }
    }
}

