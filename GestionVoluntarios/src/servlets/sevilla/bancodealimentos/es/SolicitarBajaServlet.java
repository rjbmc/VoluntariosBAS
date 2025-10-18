// Paquete: servlets.sevilla.bancodealimentos.es
package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Properties;
import java.util.UUID;

import org.mindrot.jbcrypt.BCrypt;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;

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

/**
 * Servlet para gestionar la solicitud de baja de un voluntario.
 * Utiliza HttpSession para la autenticación y contiene la lógica de negocio directamente.
 */
@WebServlet("/solicitar-baja")
public class SolicitarBajaServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private final Gson gson = new Gson();

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        JsonObject jsonResponse = new JsonObject();

        // 1. AUTENTICACIÓN: Validar la sesión del usuario
        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("usuario") == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            jsonResponse.addProperty("message", "No hay una sesión de usuario activa. Por favor, inicia sesión de nuevo.");
            response.getWriter().write(jsonResponse.toString());
            return;
        }
        String usuario = (String) session.getAttribute("usuario");

        try {
            // 2. OBTENER CONTRASEÑA: Leer la contraseña del cuerpo de la petición
            JsonObject body = gson.fromJson(request.getReader(), JsonObject.class);
            String plainPassword = body.get("password").getAsString();
            
            try (Connection conn = DatabaseUtil.getConnection()) {
                // 3. VERIFICAR CONTRASEÑA: Lógica directamente aquí
                String hashedPassword = null;
                String sqlSelectClave = "SELECT Clave FROM voluntarios WHERE Usuario = ?";
                try (PreparedStatement ps = conn.prepareStatement(sqlSelectClave)) {
                    ps.setString(1, usuario);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            hashedPassword = rs.getString("Clave");
                        }
                    }
                }
                
                if (hashedPassword == null || !BCrypt.checkpw(plainPassword, hashedPassword)) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    jsonResponse.addProperty("message", "La contraseña es incorrecta.");
                    response.getWriter().write(jsonResponse.toString());
                    LogUtil.logOperation(conn, "SOLICITUD_BAJA_FAIL", usuario, "Intento de baja con contraseña incorrecta.");
                    return;
                }

                // 4. GENERAR TOKEN DE BAJA: Crear token único y fecha de expiración (1 hora)
                String bajaToken = UUID.randomUUID().toString();
                Timestamp expiryDate = new Timestamp(System.currentTimeMillis() + 3600 * 1000);

                // 5. GUARDAR TOKEN EN BD
                String sqlUpdate = "UPDATE voluntarios SET token_baja = ?, token_baja_expiry = ? WHERE Usuario = ?";
                try (PreparedStatement psUpdate = conn.prepareStatement(sqlUpdate)) {
                    psUpdate.setString(1, bajaToken);
                    psUpdate.setTimestamp(2, expiryDate);
                    psUpdate.setString(3, usuario);
                    psUpdate.executeUpdate();
                }

                // 6. ENVIAR EMAIL: Lógica directamente aquí
                enviarEmailConfirmacion(conn, usuario, bajaToken, request);

                // 7. RESPUESTA DE ÉXITO
                jsonResponse.addProperty("success", true);
                jsonResponse.addProperty("message", "Se ha enviado un correo a tu email para confirmar la baja. Por favor, revisa tu bandeja de entrada.");
                response.getWriter().write(jsonResponse.toString());
                LogUtil.logOperation(conn, "SOLICITUD_BAJA_OK", usuario, "Se ha enviado el email de confirmación de baja.");

            } catch (SQLException e) {
                e.printStackTrace();
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                jsonResponse.addProperty("message", "Error de base de datos al procesar la solicitud.");
                response.getWriter().write(jsonResponse.toString());
            }
        } catch (JsonSyntaxException | NullPointerException e) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            jsonResponse.addProperty("message", "La solicitud no tiene el formato esperado.");
            response.getWriter().write(jsonResponse.toString());
        } catch (Exception e) {
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            jsonResponse.addProperty("message", "Ha ocurrido un error inesperado: " + e.getMessage());
            response.getWriter().write(jsonResponse.toString());
        }
    }
    
    /**
     * Método privado para construir y enviar el email de confirmación.
     */
    private void enviarEmailConfirmacion(Connection conn, String usuario, String bajaToken, HttpServletRequest request) throws SQLException, MessagingException {
        // Obtener el email del usuario
        String userEmail = null;
        String sqlSelectEmail = "SELECT Email FROM voluntarios WHERE Usuario = ?";
        try(PreparedStatement ps = conn.prepareStatement(sqlSelectEmail)) {
            ps.setString(1, usuario);
            try(ResultSet rs = ps.executeQuery()) {
                if(rs.next()) {
                    userEmail = rs.getString("Email");
                }
            }
        }

        if (userEmail == null) {
            throw new SQLException("No se pudo encontrar el email del usuario para enviar la confirmación.");
        }
        

        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.host", Config.SMTP_HOST);
        props.put("mail.smtp.port", Config.SMTP_PORT);

        Session mailSession = Session.getInstance(props, new Authenticator() {
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(Config.SMTP_USER, Config.SMTP_PASSWORD);
            }
        });

        Message message = new MimeMessage(mailSession);
        message.setFrom(new InternetAddress(Config.SMTP_USER));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(userEmail));
        message.setSubject("Confirmación de Baja - Voluntarios Banco de Alimentos de Sevilla");

        String urlBase = request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort() + request.getContextPath();
        String confirmationLink = urlBase + "/confirmacion-baja.html?token=" + bajaToken;
        String htmlContent = "<h1>Confirmación de Solicitud de Baja</h1><p>Hola,</p><p>Hemos recibido una solicitud para dar de baja tu cuenta. Para completar el proceso, haz clic en el siguiente enlace:</p><p><a href=\"" + confirmationLink + "\">Confirmar mi baja</a></p><p>Si no has solicitado esto, puedes ignorar este correo.</p>";
        message.setContent(htmlContent, "text/html; charset=utf-8");

        Transport.send(message);
    }
}

