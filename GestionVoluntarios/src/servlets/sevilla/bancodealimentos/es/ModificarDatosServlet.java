package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import com.google.gson.JsonObject;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import util.sevilla.bancodealimentos.es.DatabaseUtil;
import util.sevilla.bancodealimentos.es.LogUtil;
import util.sevilla.bancodealimentos.es.PasswordUtils;

@WebServlet("/modificar-datos")
public class ModificarDatosServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        JsonObject jsonResponse = new JsonObject();
        HttpSession session = request.getSession(false);

        if (session == null || session.getAttribute("usuario") == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        String usuario = (String) session.getAttribute("usuario");
        
        String sql = "SELECT Nombre, Apellidos, `DNI NIF`, Email, telefono, fechaNacimiento, cp, tiendaReferencia " +
                     "FROM voluntarios WHERE Usuario = ?";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, usuario);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    jsonResponse.addProperty("nombre", rs.getString("Nombre"));
                    jsonResponse.addProperty("apellidos", rs.getString("Apellidos"));
                    jsonResponse.addProperty("dni", rs.getString("DNI NIF"));
                    jsonResponse.addProperty("email", rs.getString("Email"));
                    jsonResponse.addProperty("telefono", rs.getString("telefono"));
                    jsonResponse.addProperty("fechaNacimiento", rs.getString("fechaNacimiento"));
                    jsonResponse.addProperty("cp", rs.getString("cp"));
                    jsonResponse.addProperty("tiendaReferencia", rs.getInt("tiendaReferencia"));
                    response.getWriter().write(jsonResponse.toString());
                } else {
                    response.setStatus(HttpServletResponse.SC_NOT_FOUND);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        JsonObject jsonResponse = new JsonObject();
        HttpSession session = request.getSession(false);

        if (session == null || session.getAttribute("usuario") == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        String usuario = (String) session.getAttribute("usuario");
        
        String nombre = request.getParameter("nombre");
        String apellidos = request.getParameter("apellidos");
        String nuevoEmail = request.getParameter("email");
        String telefono = request.getParameter("telefono");
        String fechaNacimiento = request.getParameter("fechaNacimiento");
        String cp = request.getParameter("cp");
        String tiendaReferenciaStr = request.getParameter("tiendaReferencia");
        String claveActual = request.getParameter("clave_actual");
        String nuevaClave = request.getParameter("nueva_clave");

        Connection conn = null;
        try {
            conn = DatabaseUtil.getConnection();
            conn.setAutoCommit(false);

            String emailActual = getEmailActual(conn, usuario);
            boolean emailHaCambiado = !nuevoEmail.equalsIgnoreCase(emailActual);
            
            // Lógica para cambiar contraseña (si aplica)
            if (nuevaClave != null && !nuevaClave.isEmpty()) {
                if (!verificarYCambiarClave(conn, usuario, claveActual, nuevaClave)) {
                    jsonResponse.addProperty("success", false);
                    jsonResponse.addProperty("message", "La contraseña actual no es correcta.");
                    response.getWriter().write(jsonResponse.toString());
                    conn.rollback();
                    return;
                }
            }

            // Lógica para actualizar datos personales
            actualizarDatosPersonales(conn, usuario, nombre, apellidos, telefono, fechaNacimiento, cp, tiendaReferenciaStr);

            // Lógica para el cambio de email
            if (emailHaCambiado) {
                iniciarCambioDeEmail(conn, usuario, nuevoEmail);
                jsonResponse.addProperty("success", true);
                jsonResponse.addProperty("message", "¡Datos guardados! Se ha enviado un correo a tu nueva dirección para verificar el cambio.");
            } else {
                jsonResponse.addProperty("success", true);
                jsonResponse.addProperty("message", "¡Datos actualizados correctamente!");
            }
            
            conn.commit();

        } catch (SQLException e) {
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) {}
            e.printStackTrace();
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            jsonResponse.addProperty("success", false);
            jsonResponse.addProperty("message", "Error de base de datos al actualizar.");
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException e) {}
        }

        response.getWriter().write(jsonResponse.toString());
    }

    private String getEmailActual(Connection conn, String usuario) throws SQLException {
        String emailActual = "";
        String sql = "SELECT Email FROM voluntarios WHERE Usuario = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, usuario);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    emailActual = rs.getString("Email");
                }
            }
        }
        return emailActual;
    }

    private boolean verificarYCambiarClave(Connection conn, String usuario, String claveActual, String nuevaClave) throws SQLException {
        String claveGuardada = "";
        try (PreparedStatement stmt = conn.prepareStatement("SELECT Clave FROM voluntarios WHERE Usuario = ?")) {
            stmt.setString(1, usuario);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) claveGuardada = rs.getString("Clave");
        }

        if (PasswordUtils.checkPassword(claveActual, claveGuardada)) {
            String nuevaClaveHasheada = PasswordUtils.hashPassword(nuevaClave);
            try (PreparedStatement stmt = conn.prepareStatement("UPDATE voluntarios SET Clave = ? WHERE Usuario = ?")) {
                stmt.setString(1, nuevaClaveHasheada);
                stmt.setString(2, usuario);
                stmt.executeUpdate();
                LogUtil.logOperation(conn, "MODIF_PASS", usuario, "Contraseña actualizada por el propio usuario.");
                return true;
            }
        }
        return false;
    }
    
    private void actualizarDatosPersonales(Connection conn, String usuario, String nombre, String apellidos, String telefono, String fechaNacimiento, String cp, String tiendaReferenciaStr) throws SQLException {
        String sql = "UPDATE voluntarios SET Nombre = ?, Apellidos = ?, telefono = ?, fechaNacimiento = ?, cp = ?, tiendaReferencia = ?, notificar = 'S' WHERE Usuario = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, nombre);
            stmt.setString(2, apellidos);
            stmt.setString(3, telefono);
            stmt.setString(4, fechaNacimiento);
            stmt.setString(5, cp);
            stmt.setInt(6, Integer.parseInt(tiendaReferenciaStr));
            stmt.setString(7, usuario);
            stmt.executeUpdate();
            LogUtil.logOperation(conn, "MODIF", usuario, "Datos personales actualizados.");
        }
    }
    
    private void iniciarCambioDeEmail(Connection conn, String usuario, String nuevoEmail) throws SQLException {
        String token = UUID.randomUUID().toString();
        String sql = "UPDATE voluntarios SET nuevo_email = ?, token_cambio_email = ? WHERE Usuario = ?";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, nuevoEmail);
            stmt.setString(2, token);
            stmt.setString(3, usuario);
            stmt.executeUpdate();
            sendEmailConfirmacionCambio(nuevoEmail, token);
            LogUtil.logOperation(conn, "CHANGE_EMAIL_REQ", usuario, "Solicitud de cambio de email a " + nuevoEmail);
        }
    }

    private void sendEmailConfirmacionCambio(String emailDestino, String token) {
        // Lógica de envío de correo (simulada o real)
        String link = "http://localhost:8080/VoluntariosBAS/confirmar-cambio-email.html?token=" + token;
        
        System.out.println("--- SIMULACIÓN DE ENVÍO DE CORREO DE CAMBIO DE EMAIL ---");
        System.out.println("Para: " + emailDestino);
        System.out.println("Asunto: Confirma tu nueva dirección de correo");
        System.out.println("Cuerpo: Por favor, haz clic en el siguiente enlace para confirmar tu nueva dirección de correo: " + link);
        System.out.println("-----------------------------------------------------");

        // Aquí iría el código real para enviar el correo con JavaMail
    }
}

