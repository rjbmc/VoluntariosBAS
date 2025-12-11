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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

@WebServlet("/nuevo-voluntario")
public class NuevoVoluntarioServlet extends HttpServlet {
    private static final long serialVersionUID = 3L; // Versión actualizada
    private static final String SP_LIST_NAME = "Voluntarios";
    private final ObjectMapper objectMapper = new ObjectMapper();

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

        try {
            LocalDate fechaNacimiento = LocalDate.parse(fechaNacimientoStr);
            if (Period.between(fechaNacimiento, LocalDate.now()).getYears() < 16) {
                sendJsonResponse(response, HttpServletResponse.SC_BAD_REQUEST, false, "Debes tener al menos 16 años para poder registrarte.");
                return;
            }
        } catch (java.time.format.DateTimeParseException e) {
            sendJsonResponse(response, HttpServletResponse.SC_BAD_REQUEST, false, "El formato de la fecha de nacimiento no es válido.");
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
                conn.rollback();
                sendJsonResponse(response, HttpServletResponse.SC_CONFLICT, false, "El nombre de usuario o el DNI ya están registrados en una cuenta activa y verificada.");
                return;
            }

            String hashedPassword = PasswordUtils.hashPassword(clave);
            String verificationToken = UUID.randomUUID().toString();
            
            if (existingUser != null && !isInactive && !isVerified) {
                // TODO: Lógica para reenviar el correo de verificación si el usuario existe pero no está verificado
            }

            if (existingUser != null && isInactive) {
                isReactivation = true;
                // TODO: Lógica de UPDATE para reactivar un usuario. Por ejemplo:
                // String updateSql = "UPDATE voluntarios SET ... WHERE Usuario = ?";

            } else {
                sqlRowUuid = UUID.randomUUID().toString();
                // TODO: Lógica de INSERT para un nuevo usuario. Por ejemplo:
                // String insertSql = "INSERT INTO voluntarios(...) VALUES (...)";
            }

            conn.commit();

            try {
                Map<String, Object> spData = new HashMap<>();
                spData.put("Title", nombre);
                spData.put("Apellidos", apellidos);
                spData.put("DNI_x0020_NIF", dni);
                spData.put("TiendaReferenciaId", tiendaReferencia);
                spData.put("Email", email);
                spData.put("Telefono", telefono);
                spData.put("FechaNacimiento", fechaNacimientoStr);
                spData.put("CP", cp);
                spData.put("EmailVerificado", false);

                String listId = SharepointUtil.getListId(SharepointUtil.SP_SITE_ID_VOLUNTARIOS, SP_LIST_NAME);
                if (listId == null) {
                    throw new Exception("Lista '" + SP_LIST_NAME + "' no encontrada.");
                }

                if (isReactivation) {
                     if (sqlRowUuid != null) {
                        String itemId = SharepointUtil.findItemIdByFieldValue(SharepointUtil.SP_SITE_ID_VOLUNTARIOS, listId, "SqlRowUUID", sqlRowUuid);
                        if (itemId != null) {
                            spData.put("FechaBaja", null);
                            FieldValueSet fieldsToUpdate = new FieldValueSet();
                            fieldsToUpdate.setAdditionalData(spData);
                            SharepointUtil.updateListItem(SharepointUtil.SP_SITE_ID_VOLUNTARIOS, listId, itemId, fieldsToUpdate);
                        } else {
                             spData.put("SqlRowUUID", sqlRowUuid);
                             FieldValueSet fieldsToCreate = new FieldValueSet();
                             fieldsToCreate.setAdditionalData(spData);
                             SharepointUtil.createListItem(SharepointUtil.SP_SITE_ID_VOLUNTARIOS, listId, fieldsToCreate);
                        }
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
                LogUtil.logOperation(conn, "SP_REPLICATION_ERROR", "SYSTEM", "Fallo en replicación para UUID: " + sqlRowUuid + ". Causa: " + e.getMessage());
                e.printStackTrace();
            }

            sendVerificationEmail(request, email, usuario, verificationToken);

            sendJsonResponse(response, HttpServletResponse.SC_OK, true, "¡Registro casi completo! Se ha enviado un correo de verificación a tu email. Por favor, revísalo para activar tu cuenta.", email);
            
        } catch (SQLException e) {
            e.printStackTrace();
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) { ex.printStackTrace(); }
            sendJsonResponse(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, false, "Error de base de datos durante el registro.");
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException e) { e.printStackTrace(); }
        }
    }
    
    private void sendVerificationEmail(HttpServletRequest request, String emailDestino, String usuario, String token){
        // ... (código de envío de email sin cambios)
    }
    
    private void sendJsonResponse(HttpServletResponse response, int status, boolean success, String message) throws IOException {
        response.setStatus(status);
        ObjectNode jsonResponse = objectMapper.createObjectNode();
        jsonResponse.put("success", success);
        jsonResponse.put("message", message);
        objectMapper.writeValue(response.getWriter(), jsonResponse);
    }
    
    private void sendJsonResponse(HttpServletResponse response, int status, boolean success, String message, String email) throws IOException {
        response.setStatus(status);
        ObjectNode jsonResponse = objectMapper.createObjectNode();
        jsonResponse.put("success", success);
        jsonResponse.put("message", message);
        jsonResponse.put("email", email);
        objectMapper.writeValue(response.getWriter(), jsonResponse);
    }
}
