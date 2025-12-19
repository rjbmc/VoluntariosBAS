package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import util.sevilla.bancodealimentos.es.DatabaseUtil;
import util.sevilla.bancodealimentos.es.LogUtil;

/**
 * Servlet para la gestión administrativa de voluntarios.
 * Corregido para coincidir con los nombres de columna reales de la tabla 'voluntarios'
 * y manejar fechas de baja inválidas (0000-00-00).
 */
@WebServlet("/admin-voluntarios")
public class AdminVoluntariosServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    
    private static final Logger logger = LoggerFactory.getLogger(AdminVoluntariosServlet.class);
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * DTO para representar al Voluntario.
     */
    public static class Voluntario {
        public String usuario;
        public String nombre;
        public String apellidos;
        public String dni;
        public String email;
        public String telefono;
        public String cp;
        public String fechaNacimiento;
        public String esAdmin;
        public String fechaBaja; 
    }

    private boolean isAdmin(HttpSession session) {
        if (session == null || session.getAttribute("usuario") == null) return false;
        Object isAdminAttr = session.getAttribute("isAdmin");
        if (isAdminAttr instanceof Boolean) return (Boolean) isAdminAttr;
        if (isAdminAttr instanceof String) return "S".equals(isAdminAttr);
        return false;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        HttpSession session = request.getSession(false);
        if (!isAdmin(session)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        List<Voluntario> voluntarios = new ArrayList<>();
        
        // Ajustado a los nombres reales de la tabla con alias para mantener compatibilidad Java/Frontend
        String sql = "SELECT Usuario AS usuario, Nombre AS nombre, Apellidos AS apellidos, `DNI NIF` AS dni, " +
                     "Email AS email, telefono, cp, fechaNacimiento, administrador AS esAdmin, fecha_baja AS fechaBaja " +
                     "FROM voluntarios ORDER BY Apellidos, Nombre";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            
            while (rs.next()) {
                Voluntario v = new Voluntario();
                v.usuario = rs.getString("usuario");
                v.nombre = rs.getString("nombre");
                v.apellidos = rs.getString("apellidos");
                v.dni = rs.getString("dni");
                v.email = rs.getString("email");
                v.telefono = rs.getString("telefono");
                v.cp = rs.getString("cp");
                v.fechaNacimiento = rs.getString("fechaNacimiento");
                v.esAdmin = rs.getString("esAdmin");
                
                // Normalización de la fecha de baja: 0000-00-00 se trata como null
                String fb = rs.getString("fechaBaja");
                if ("0000-00-00".equals(fb)) {
                    v.fechaBaja = null;
                } else {
                    v.fechaBaja = fb;
                }
                
                voluntarios.add(v);
            }
            mapper.writeValue(response.getWriter(), voluntarios);
        } catch (SQLException e) {
            logger.error("Error SQL al listar voluntarios", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        
        Map<String, Object> jsonResponse = new HashMap<>();
        HttpSession session = request.getSession(false);

        if (!isAdmin(session)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            jsonResponse.put("success", false);
            jsonResponse.put("message", "Acceso denegado.");
            mapper.writeValue(response.getWriter(), jsonResponse);
            return;
        }
        
        String adminUser = (String) session.getAttribute("usuario");
        String action = request.getParameter("action");

        if ("save".equals(action)) {
            actualizarDatosVoluntario(request, jsonResponse, adminUser);
        } else if ("toggleAdmin".equals(action)) {
            cambiarEstadoAdmin(request, jsonResponse, adminUser);
        }

        mapper.writeValue(response.getWriter(), jsonResponse);
    }

    private void actualizarDatosVoluntario(HttpServletRequest request, Map<String, Object> jsonResponse, String adminUser) {
        String usuario = request.getParameter("usuario");
        String nombre = request.getParameter("nombre");
        String apellidos = request.getParameter("apellidos");
        String dni = request.getParameter("dni");
        String email = request.getParameter("email");
        String telefono = request.getParameter("telefono");
        String cp = request.getParameter("cp");
        String fechaNacimiento = request.getParameter("fechaNacimiento");

        // Ajustado a los nombres reales (Case sensitive y columnas con espacios)
        String sql = "UPDATE voluntarios SET Nombre=?, Apellidos=?, `DNI NIF`=?, Email=?, telefono=?, cp=?, fechaNacimiento=? WHERE Usuario=?";
        
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, nombre);
            stmt.setString(2, apellidos);
            stmt.setString(3, dni);
            stmt.setString(4, email);
            stmt.setString(5, telefono);
            stmt.setString(6, cp);
            stmt.setString(7, fechaNacimiento);
            stmt.setString(8, usuario);
            
            int rows = stmt.executeUpdate();
            jsonResponse.put("success", rows > 0);
            jsonResponse.put("message", rows > 0 ? "Datos actualizados correctamente." : "No se encontró el usuario.");
            
            if (rows > 0) {
                LogUtil.logOperation(conn, "ADMIN_EDIT_VOL", adminUser, "Editado voluntario: " + usuario);
            }
        } catch (SQLException e) {
            logger.error("Error al actualizar voluntario", e);
            jsonResponse.put("success", false);
            jsonResponse.put("message", "Error en base de datos: " + e.getMessage());
        }
    }

    private void cambiarEstadoAdmin(HttpServletRequest request, Map<String, Object> jsonResponse, String adminUser) {
        String usuario = request.getParameter("usuario");
        String esAdmin = request.getParameter("esAdmin");

        // Ajustado a nombres reales: administrador y Usuario
        String sql = "UPDATE voluntarios SET administrador=? WHERE Usuario=?";
        
        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            
            stmt.setString(1, esAdmin);
            stmt.setString(2, usuario);
            
            int rows = stmt.executeUpdate();
            jsonResponse.put("success", rows > 0);
            jsonResponse.put("message", "Permisos actualizados.");
            
            if (rows > 0) {
                LogUtil.logOperation(conn, "ADMIN_TOGGLE_ADMIN", adminUser, "Cambiado rol admin a " + esAdmin + " para: " + usuario);
            }
        } catch (SQLException e) {
            logger.error("Error al cambiar rol admin", e);
            jsonResponse.put("success", false);
            jsonResponse.put("message", "Error en base de datos.");
        }
    }
}