package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.Period;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.google.gson.JsonObject;
import com.microsoft.graph.models.FieldValueSet;

import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import util.sevilla.bancodealimentos.es.DatabaseUtil;
import util.sevilla.bancodealimentos.es.LogUtil;
import util.sevilla.bancodealimentos.es.PasswordUtils;
import util.sevilla.bancodealimentos.es.SharepointUtil;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@WebServlet("/nuevo-voluntario")
public class NuevoVoluntarioServlet extends HttpServlet {
    private static final Logger logger = LogManager.getLogger(NuevoVoluntarioServlet.class);
    private static final long serialVersionUID = 2L; // Versión actualizada
    private static final String SP_LIST_NAME = "Voluntarios";

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String usuario = request.getParameter("usuario");
        String nombre = request.getParameter("nombre");
        String apellidos = request.getParameter("apellidos");
        String dni = request.getParameter("dni");
        String clave = request.getParameter("clave");
        String email = request.getParameter("email");
        String telefono = request.getParameter("telefono");
        String fechaNacimientoStr = request.getParameter("fechaNacimiento");
        String cp = request.getParameter("cp");
        int tiendaReferencia = Integer.parseInt(request.getParameter("punto_id"));

        JsonObject jsonResponse = new JsonObject();
        
        try {
            LocalDate fechaNacimiento = LocalDate.parse(fechaNacimientoStr);
            if (Period.between(fechaNacimiento, LocalDate.now()).getYears() < 16) {
                jsonResponse.addProperty("success", false);
                jsonResponse.addProperty("message", "Debes tener al menos 16 años para poder registrarte.");
                response.getWriter().write(jsonResponse.toString());
                return;
            }
        } catch (java.time.format.DateTimeParseException e) {
            jsonResponse.addProperty("success", false);
            jsonResponse.addProperty("message", "El formato de la fecha de nacimiento no es válido.");
            response.getWriter().write(jsonResponse.toString());
            return;
        }
        
        Connection conn = null;
        String sqlRowUuid = null;
        boolean isReactivation = false;

        try {
        	conn = DatabaseUtil.getConnection();
            conn.setAutoCommit(false);

            String checkSql = "SELECT Usuario, fecha_baja, verificado, SqlRowUUID FROM voluntarios WHERE Usuario = ? OR `DNI NIF` = ?";
            String existingUser = null;
            boolean isInactive = false;
            boolean isVerified = false; 

            try (PreparedStatement psCheck = conn.prepareStatement(checkSql)) {
                psCheck.setString(1, usuario);
                psCheck.setString(2, dni);
                try (ResultSet rs = psCheck.executeQuery()) {
                    if (rs.next()) {
                        existingUser = rs.getString("Usuario");
                        isInactive = rs.getDate("fecha_baja") != null;
                        isVerified = "S".equals(rs.getString("verificado"));
                        sqlRowUuid = rs.getString("SqlRowUUID");
                    }
                }
            }

            if (existingUser != null && !isInactive && isVerified) {
                jsonResponse.addProperty("success", false);
                jsonResponse.addProperty("message", "El nombre de usuario o el DNI ya están registrados en una cuenta activa y verificada.");
                conn.rollback();
                response.getWriter().write(jsonResponse.toString());
                return;
            }

            String hashedPassword = PasswordUtils.hashPassword(clave);
            String verificationToken = UUID.randomUUID().toString();
            
            if (existingUser != null && !isInactive && !isVerified) {
                // ... (lógica para reenviar verificación sin cambios)
            }

            if (existingUser != null && isInactive) {
                isReactivation = true;
                // ... (lógica de update local sin cambios)

            } else {
                sqlRowUuid = UUID.randomUUID().toString(); // Usamos el método nativo
                // ... (lógica de insert local sin cambios)
            }

            conn.commit();

            // *** COMIENZA LA NUEVA LÓGICA DE REPLICACIÓN A SHAREPOINT ***
            try {
                Map<String, Object> spData = new HashMap<>();
                spData.put("field_1", nombre);
                spData.put("field_2", apellidos);
                spData.put("field_3", dni);
                spData.put("field_5", tiendaReferencia);
                spData.put("field_6", email);
                spData.put("field_7", telefono);
                spData.put("field_8", fechaNacimientoStr);
                spData.put("field_9", cp);
                spData.put("Verificado", "No");

                if (isReactivation) {
                    // --- REPLICACIÓN DE REACTIVACIÓN (UPDATE) ---
                    if (sqlRowUuid != null) {
                        spData.put("field_21", null);
                        SharepointReplicationUtil.replicate(conn, SharepointUtil.SP_SITE_ID_VOLUNTARIOS, "voluntarios", spData, SharepointReplicationUtil.Operation.UPDATE, sqlRowUuid);
                    } else {
                        logger.warn("No se encontró SqlRowUUID para reactivar al usuario '{}' - no se replicará la reactivación", usuario);
                    }
                } else {
                    if (sqlRowUuid != null) {
                        spData.put("SqlRowUUID", sqlRowUuid);
                        FieldValueSet fieldsToCreate = new FieldValueSet();
                        fieldsToCreate.setAdditionalData(spData);
                        SharepointUtil.createListItem(SharepointUtil.SP_SITE_ID_VOLUNTARIOS, listId, fieldsToCreate);
                    }
                }
            } catch (Exception e) {
                logger.warn("Fallo al iniciar el proceso de replicación a SharePoint para el UUID {}: {}", sqlRowUuid, e.getMessage());
            }
            // *** FIN DE LA NUEVA LÓGICA DE REPLICACIÓN ***

            sendVerificationEmail(request, email, usuario, verificationToken);

            jsonResponse.addProperty("success", true);
            jsonResponse.addProperty("message", "¡Registro casi completo! Se ha enviado un correo de verificación a tu email. Por favor, revísalo para activar tu cuenta.");
            jsonResponse.addProperty("email", email); 
            
        } catch (SQLException e) {
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) { logger.error("Error al hacer rollback en nuevo-voluntario", ex); }
            
            if (e.getErrorCode() == 1062) {
                 jsonResponse.addProperty("success", false);
                 jsonResponse.addProperty("message", "El nombre de usuario, DNI o Email ya están registrados.");
            } else {
                response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                jsonResponse.addProperty("success", false);
                jsonResponse.addProperty("message", "Error de base de datos. Inténtalo más tarde.");
            }
            logger.error("Error de BD en nuevo-voluntario", e);
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException e) { logger.error("Error cerrando conexión en nuevo-voluntario", e); }
        }

        response.getWriter().write(jsonResponse.toString());
    }
    
    private void sendVerificationEmail(HttpServletRequest request, String emailDestino, String usuario, String token){
        // ... (código de envío de email sin cambios)
    }
}