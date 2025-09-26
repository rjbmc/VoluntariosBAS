package util.sevilla.bancodealimentos.es;

import org.mindrot.jbcrypt.BCrypt;

/**
* Clase de utilidad para manejar operaciones de contraseñas de forma segura.
* Proporciona métodos para crear un "hash" de una contraseña y verificarla.
*/
public class PasswordUtils {

 /**
  * Crea un hash de una contraseña en texto plano usando BCrypt.
  *
  * @param plainPassword La contraseña en texto plano.
  * @return Un string con el hash de la contraseña.
  */
 public static String hashPassword(String plainPassword) {
     // El método gensalt() genera una "sal" aleatoria para cada hash.
     // El número 12 es el "work factor", que indica la complejidad.
     // Un valor entre 10 y 12 es el recomendado actualmente.
     return BCrypt.hashpw(plainPassword, BCrypt.gensalt(12));
 }

 /**
  * Verifica si una contraseña en texto plano coincide con un hash.
  *
  * @param plainPassword La contraseña en texto plano a verificar.
  * @param hashedPassword El hash almacenado en la base de datos.
  * @return true si la contraseña coincide, false en caso contrario.
  */
 public static boolean checkPassword(String plainPassword, String hashedPassword) {
     return BCrypt.checkpw(plainPassword, hashedPassword);
 }
}