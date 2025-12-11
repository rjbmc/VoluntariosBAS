package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;

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

@WebServlet("/voluntario-detalles")
public class VoluntarioDetallesServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    
    // 1. Logger SLF4J
    private static final Logger logger = LoggerFactory.getLogger(VoluntarioDetallesServlet.class);
    
    // 2. Jackson ObjectMapper
    private final ObjectMapper mapper = new ObjectMapper();

    // DTO (Data Transfer Object) para estructurar la respuesta JSON limpiamente
    // Usar una clase en lugar de un JsonObject genérico previene errores de nombres de campos.
    public static class VoluntarioDTO {
        public String nombre;
        public String apellidos;
        public String dni;
        public String email;
        public String telefono;
        public String fechaNacimiento;
        public String cp;
        public Integer tiendaReferencia; // Usamos Integer para permitir nulls
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("usuario") == null) {
            logger.warn("Intento de acceso no autorizado a VoluntarioDetalles. IP: {}", request.getRemoteAddr());
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "No hay una sesión de usuario activa.");
            return;
        }

        String usuarioEnSesion = (String) session.getAttribute("usuario");
        
        // Log de depuración (útil en desarrollo, invisible en producción si el nivel es INFO)
        logger.debug("Recuperando detalles para el usuario: {}", usuarioEnSesion);

        String sql = "SELECT Nombre, Apellidos, `DNI NIF`, Email, telefono, fechaNacimiento, cp, tiendaReferencia FROM voluntarios WHERE Usuario = ?";

        try (Connection conn = DatabaseUtil.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, usuarioEnSesion);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    VoluntarioDTO dto = new VoluntarioDTO();
                    dto.nombre = rs.getString("Nombre");
                    dto.apellidos = rs.getString("Apellidos");
                    dto.dni = rs.getString("DNI NIF");
                    dto.email = rs.getString("Email");
                    dto.telefono = rs.getString("telefono");
                    
                    // Manejo de fecha con formateo
                    java.sql.Date fechaNac = rs.getDate("fechaNacimiento");
                    if (fechaNac != null) {
                        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
                        dto.fechaNacimiento = sdf.format(fechaNac);
                    } else {
                        dto.fechaNacimiento = "";
                    }
                    
                    dto.cp = rs.getString("cp");

                    // Manejo de enteros nulos (wasNull es necesario para tipos primitivos en JDBC)
                    int tiendaId = rs.getInt("tiendaReferencia");
                    if (!rs.wasNull()) {
                        dto.tiendaReferencia = tiendaId;
                    } else {
                        dto.tiendaReferencia = null;
                    }

                    // 3. Serialización automática con Jackson
                    mapper.writeValue(response.getWriter(), dto);

                } else {
                    logger.warn("El usuario en sesión '{}' no se encontró en la tabla 'voluntarios'.", usuarioEnSesion);
                    response.sendError(HttpServletResponse.SC_NOT_FOUND, "Usuario no encontrado.");
                } 
            }
        } catch (SQLException e) {
            // Logueamos la excepción completa con su traza
            logger.error("Error de base de datos al recuperar detalles del voluntario {}", usuarioEnSesion, e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Error interno al consultar la base de datos.");
        }
    }
}