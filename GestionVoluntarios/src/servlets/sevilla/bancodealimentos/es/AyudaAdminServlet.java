package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

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
import util.sevilla.bancodealimentos.es.LogUtil;

/**
 * Servlet para que los usuarios envíen una petición de ayuda al administrador.
 */
@WebServlet("/ayuda-admin")
public class AyudaAdminServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    
    // 1. Logger SLF4J
    private static final Logger logger = LoggerFactory.getLogger(AyudaAdminServlet.class);
    
    // 2. Jackson ObjectMapper
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        request.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String nombre = request.getParameter("nombre");
        String email = request.getParameter("email");
        String problema = request.getParameter("problema");
        
        Map<String, Object> jsonResponse = new HashMap<>();

        try {
            logger.info("Recibida solicitud de ayuda de: {} ({})", nombre, email);
            
            sendHelpEmail(nombre, email, problema);
            
            String operador = (email != null && !email.isEmpty()) ? email : "desconocido";
            
            // Registro en base de datos
            LogUtil.logOperation("AYUDA", operador, "Solicitud de ayuda enviada por: " + nombre);

            response.setStatus(HttpServletResponse.SC_OK);
            jsonResponse.put("success", true);
            jsonResponse.put("message", "Tu solicitud de ayuda ha sido enviada al administrador.");

        } catch (Exception e) {
            // Logueamos el error completo con su traza
            logger.error("Error al enviar correo de ayuda de {}", email, e);
            
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            jsonResponse.put("success", false);
            jsonResponse.put("message", "No se pudo enviar la solicitud de ayuda. Por favor, inténtalo más tarde.");
        }
        
        // 3. Respuesta JSON automática con Jackson
        mapper.writeValue(response.getWriter(), jsonResponse);
    }

    private void sendHelpEmail(String nombre, String email, String problema) throws MessagingException {
        final String username = Config.SMTP_USER;
        final String password = Config.SMTP_PASSWORD;
        final String adminEmail = Config.SISTEMAS_EMAIL;

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
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(adminEmail));
        message.setSubject("Solicitud de Ayuda - App VoluntariosBAS");

        String emailBody = "Se ha recibido una nueva solicitud de ayuda desde la aplicación de voluntarios.<br><br>"
                          + "<strong>Nombre del Voluntario:</strong> " + escapeHtml(nombre) + "<br>"
                          + "<strong>Email de Contacto:</strong> " + escapeHtml(email) + "<br><br>"
                          + "<strong>Descripción del Problema:</strong><br>"
                          + "<pre style=\"font-family: sans-serif; white-space: pre-wrap;\">" + escapeHtml(problema) + "</pre>"
                          + "<strong>La dirección de correo desde donde se envía este mensaje es de sólo envío. Por favor, no la uses para responder.</strong><br>";
        
        message.setContent(emailBody, "text/html; charset=utf-8");
        Transport.send(message);
        
        logger.debug("Correo de ayuda enviado correctamente a {}", adminEmail);
    }
    
    // Método auxiliar para evitar inyección HTML básica en el correo
    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                   .replace("<", "&lt;")
                   .replace(">", "&gt;")
                   .replace("\"", "&quot;")
                   .replace("'", "&#39;");
    }
}